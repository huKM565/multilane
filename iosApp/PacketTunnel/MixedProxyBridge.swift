import Darwin
import Foundation
import os

final class MixedProxyBridge: @unchecked Sendable {
    struct Credentials {
        let username: String
        let password: String

        init?(username: String?, password: String?) {
            guard let username, !username.isEmpty, let password, !password.isEmpty else {
                return nil
            }
            self.username = username
            self.password = password
        }
    }

    private let listenHost: String
    private let listenPort: Int
    private let backendPort: Int
    private let clientLogin: Credentials?
    private let backendLogin: Credentials?
    private let log = Logger(subsystem: "org.proofkit.app", category: "mixed")

    private let lock = NSLock()
    private var stopped = false
    private var listenFd: Int32 = -1
    private var activeFds = Set<Int32>()

    init(
        listenHost: String = "0.0.0.0",
        listenPort: Int,
        backendPort: Int,
        clientLogin: Credentials?,
        backendLogin: Credentials?
    ) {
        self.listenHost = listenHost.isEmpty ? "0.0.0.0" : listenHost
        self.listenPort = listenPort
        self.backendPort = backendPort
        self.clientLogin = clientLogin
        self.backendLogin = backendLogin
    }

    func start() throws {
        lock.lock()
        stopped = false
        lock.unlock()

        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else {
            throw Self.posixError("socket failed", code: errno)
        }

        var reuse: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        Self.disableSigPipe(fd)

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(listenPort).bigEndian
        let resolvedAddr = listenHost == "0.0.0.0" ? INADDR_ANY : inet_addr(listenHost)
        addr.sin_addr = in_addr(s_addr: resolvedAddr)

        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else {
            let code = errno
            Darwin.close(fd)
            throw Self.posixError("bind \(listenHost):\(listenPort) failed", code: code)
        }
        guard Darwin.listen(fd, 128) == 0 else {
            let code = errno
            Darwin.close(fd)
            throw Self.posixError("listen \(listenHost):\(listenPort) failed", code: code)
        }

        lock.lock()
        listenFd = fd
        lock.unlock()

        log.info("mixed proxy listening on \(self.listenHost, privacy: .public):\(self.listenPort, privacy: .public)")

        DispatchQueue(label: "org.proofkit.mixed.accept", qos: .userInitiated).async { [weak self] in
            self?.acceptLoop(listenFd: fd)
        }
    }

    func stop() {
        lock.lock()
        stopped = true
        let lfd = listenFd
        listenFd = -1
        let clients = Array(activeFds)
        activeFds.removeAll()
        lock.unlock()

        if lfd >= 0 {
            Darwin.shutdown(lfd, SHUT_RDWR)
            Darwin.close(lfd)
        }
        for cfd in clients {
            Darwin.shutdown(cfd, SHUT_RDWR)
            Darwin.close(cfd)
        }
    }

    private func isStopped() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    private func track(_ fd: Int32) {
        lock.lock()
        if !stopped {
            activeFds.insert(fd)
        }
        lock.unlock()
    }

    private func closeTracked(_ fd: Int32) {
        lock.lock()
        activeFds.remove(fd)
        lock.unlock()
        Darwin.close(fd)
    }

    private func acceptLoop(listenFd: Int32) {
        while !isStopped() {
            var clientAddr = sockaddr_storage()
            var len = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let clientFd = withUnsafeMutablePointer(to: &clientAddr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.accept(listenFd, $0, &len)
                }
            }
            if clientFd < 0 {
                if isStopped() || errno == EBADF || errno == EINVAL {
                    break
                }
                continue
            }
            Self.disableSigPipe(clientFd)
            track(clientFd)
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self else {
                    Darwin.close(clientFd)
                    return
                }
                self.handleClient(clientFd)
                self.closeTracked(clientFd)
            }
        }
    }

    private func handleClient(_ clientFd: Int32) {
        var firstByte: UInt8 = 0
        guard Self.readExact(clientFd, into: &firstByte, count: 1) else { return }
        if firstByte == 0x05 {
            handleSocks5(clientFd)
        } else {
            handleHttp(clientFd, firstByte: firstByte)
        }
    }

    private func handleSocks5(_ clientFd: Int32) {
        var countByte: UInt8 = 0
        guard Self.readExact(clientFd, into: &countByte, count: 1) else { return }
        let methodCount = Int(countByte)
        var methods = [UInt8](repeating: 0, count: methodCount)
        if methodCount > 0, !Self.readBytes(clientFd, into: &methods, count: methodCount) { return }

        if let login = clientLogin {
            guard methods.contains(0x02) else {
                _ = Self.writeAll(clientFd, [0x05, 0xFF])
                return
            }
            guard Self.writeAll(clientFd, [0x05, 0x02]) else { return }
            guard verifySocksAuth(clientFd, expected: login) else { return }
        } else {
            guard methods.contains(0x00) else {
                _ = Self.writeAll(clientFd, [0x05, 0xFF])
                return
            }
            guard Self.writeAll(clientFd, [0x05, 0x00]) else { return }
        }

        guard let backendFd = openBackendSocks() else { return }
        track(backendFd)
        defer { closeTracked(backendFd) }
        guard negotiateBackendSocks(backendFd) else { return }
        relayBoth(clientFd, backendFd)
    }

    private func verifySocksAuth(_ fd: Int32, expected: Credentials) -> Bool {
        var header = [UInt8](repeating: 0, count: 2)
        guard Self.readBytes(fd, into: &header, count: 2), header[0] == 0x01 else { return false }
        let ulen = Int(header[1])
        var uBytes = [UInt8](repeating: 0, count: ulen)
        if ulen > 0, !Self.readBytes(fd, into: &uBytes, count: ulen) { return false }
        var plenByte: UInt8 = 0
        guard Self.readExact(fd, into: &plenByte, count: 1) else { return false }
        let plen = Int(plenByte)
        var pBytes = [UInt8](repeating: 0, count: plen)
        if plen > 0, !Self.readBytes(fd, into: &pBytes, count: plen) { return false }
        let user = String(bytes: uBytes, encoding: .utf8)
        let pass = String(bytes: pBytes, encoding: .utf8)
        let ok = (user == expected.username && pass == expected.password)
        _ = Self.writeAll(fd, [0x01, ok ? 0x00 : 0x01])
        return ok
    }

    private func handleHttp(_ clientFd: Int32, firstByte: UInt8) {
        guard let head = readHttpHead(clientFd, firstByte: firstByte) else { return }
        let lines = head.headerText.components(separatedBy: "\r\n")
        let requestLine = lines.first ?? ""
        let parts = requestLine.split(separator: " ", omittingEmptySubsequences: false)
        guard parts.count >= 3 else {
            _ = Self.writeHttpResponse(clientFd, status: "400 Bad Request")
            return
        }
        let method = String(parts[0]).uppercased()
        let rawTarget = String(parts[1])
        let version = String(parts[2])
        let headers = lines.dropFirst().filter { !$0.isEmpty }

        if let expected = clientLogin, !verifyHttpAuth(headers: headers, expected: expected) {
            _ = Self.writeHttpResponse(
                clientFd,
                status: "407 Proxy Authentication Required",
                extraHeaders: ["Proxy-Authenticate: Basic realm=\"MultiLane\""]
            )
            return
        }

        if method == "CONNECT" {
            guard let target = Self.parseHostPort(rawTarget, defaultPort: 443) else {
                _ = Self.writeHttpResponse(clientFd, status: "400 Bad Request")
                return
            }
            guard let backendFd = connectUpstreamTarget(host: target.0, port: target.1) else {
                _ = Self.writeHttpResponse(clientFd, status: "502 Bad Gateway")
                return
            }
            track(backendFd)
            defer { closeTracked(backendFd) }
            guard Self.writeAll(clientFd, Array("\(version) 200 Connection Established\r\n\r\n".utf8)) else {
                return
            }
            if !head.trailing.isEmpty, !Self.writeAll(backendFd, [UInt8](head.trailing)) {
                return
            }
            relayBoth(clientFd, backendFd)
            return
        }

        guard let parsed = Self.parseHttpRequestTarget(rawTarget: rawTarget, headers: headers) else {
            _ = Self.writeHttpResponse(clientFd, status: "400 Bad Request")
            return
        }
        guard let backendFd = connectUpstreamTarget(host: parsed.host, port: parsed.port) else {
            _ = Self.writeHttpResponse(clientFd, status: "502 Bad Gateway")
            return
        }
        track(backendFd)
        defer { closeTracked(backendFd) }

        let forwarded = Self.buildForwardedHttpRequest(
            method: method,
            path: parsed.path,
            version: version,
            hostHeader: parsed.hostHeader,
            headers: headers
        )
        guard Self.writeAll(backendFd, Array(forwarded.utf8)) else { return }
        if !head.trailing.isEmpty, !Self.writeAll(backendFd, [UInt8](head.trailing)) {
            return
        }

        let group = DispatchGroup()
        group.enter()
        DispatchQueue.global(qos: .userInitiated).async {
            Self.relayStream(from: clientFd, to: backendFd)
            group.leave()
        }
        Self.relayStream(from: backendFd, to: clientFd)
        Darwin.shutdown(backendFd, SHUT_RDWR)
        Darwin.shutdown(clientFd, SHUT_RDWR)
        group.wait()
    }

    private func readHttpHead(_ fd: Int32, firstByte: UInt8) -> (headerText: String, trailing: Data)? {
        var data = Data([firstByte])
        let separator = Data([0x0D, 0x0A, 0x0D, 0x0A])
        var buffer = [UInt8](repeating: 0, count: 4096)
        while data.count <= 65_536 {
            if let range = data.range(of: separator) {
                let headerData = data.subdata(in: 0..<range.lowerBound)
                guard let headerText = String(data: headerData, encoding: .utf8) else { return nil }
                let trailing = data.subdata(in: range.upperBound..<data.count)
                return (headerText, trailing)
            }
            let n = Darwin.recv(fd, &buffer, buffer.count, 0)
            if n <= 0 { return nil }
            data.append(contentsOf: buffer[0..<n])
        }
        return nil
    }

    private func verifyHttpAuth(headers: [String], expected: Credentials) -> Bool {
        for line in headers {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces)
            guard name.caseInsensitiveCompare("Proxy-Authorization") == .orderedSame else { continue }
            let val = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            guard let space = val.firstIndex(of: " ") else { return false }
            let scheme = val[..<space]
            guard scheme.caseInsensitiveCompare("Basic") == .orderedSame else { return false }
            let encoded = val[val.index(after: space)...].trimmingCharacters(in: .whitespaces)
            guard let decodedData = Data(base64Encoded: encoded),
                  let decoded = String(data: decodedData, encoding: .utf8),
                  let sep = decoded.firstIndex(of: ":") else {
                return false
            }
            let user = String(decoded[..<sep])
            let pass = String(decoded[decoded.index(after: sep)...])
            return user == expected.username && pass == expected.password
        }
        return false
    }

    private func openBackendSocks() -> Int32? {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        Self.disableSigPipe(fd)
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(backendPort).bigEndian
        addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0
            }
        }
        if !ok {
            Darwin.close(fd)
            return nil
        }
        return fd
    }

    private func negotiateBackendSocks(_ fd: Int32) -> Bool {
        guard let login = backendLogin else {
            guard Self.writeAll(fd, [0x05, 0x01, 0x00]) else { return false }
            var reply = [UInt8](repeating: 0, count: 2)
            return Self.readBytes(fd, into: &reply, count: 2) && reply[0] == 0x05 && reply[1] == 0x00
        }
        guard Self.writeAll(fd, [0x05, 0x01, 0x02]) else { return false }
        var reply = [UInt8](repeating: 0, count: 2)
        guard Self.readBytes(fd, into: &reply, count: 2), reply[0] == 0x05, reply[1] == 0x02 else {
            return false
        }
        let uBytes = Array(login.username.utf8)
        let pBytes = Array(login.password.utf8)
        guard uBytes.count <= 255, pBytes.count <= 255 else { return false }
        var auth = [UInt8]()
        auth.append(0x01)
        auth.append(UInt8(uBytes.count))
        auth.append(contentsOf: uBytes)
        auth.append(UInt8(pBytes.count))
        auth.append(contentsOf: pBytes)
        guard Self.writeAll(fd, auth) else { return false }
        var authReply = [UInt8](repeating: 0, count: 2)
        return Self.readBytes(fd, into: &authReply, count: 2) && authReply[0] == 0x01 && authReply[1] == 0x00
    }

    private func connectUpstreamTarget(host: String, port: Int) -> Int32? {
        guard let fd = openBackendSocks() else { return nil }
        guard negotiateBackendSocks(fd) else {
            Darwin.close(fd)
            return nil
        }
        var req = [UInt8]()
        req.append(0x05)
        req.append(0x01)
        req.append(0x00)
        var ipv4 = in_addr()
        var ipv6 = in6_addr()
        if inet_pton(AF_INET, host, &ipv4) == 1 {
            req.append(0x01)
            withUnsafeBytes(of: &ipv4) { req.append(contentsOf: $0) }
        } else if inet_pton(AF_INET6, host, &ipv6) == 1 {
            req.append(0x04)
            withUnsafeBytes(of: &ipv6) { req.append(contentsOf: $0) }
        } else {
            let hostBytes = Array(host.utf8)
            guard hostBytes.count <= 255 else {
                Darwin.close(fd)
                return nil
            }
            req.append(0x03)
            req.append(UInt8(hostBytes.count))
            req.append(contentsOf: hostBytes)
        }
        req.append(UInt8((port >> 8) & 0xFF))
        req.append(UInt8(port & 0xFF))

        guard Self.writeAll(fd, req) else {
            Darwin.close(fd)
            return nil
        }

        var head = [UInt8](repeating: 0, count: 4)
        guard Self.readBytes(fd, into: &head, count: 4), head[0] == 0x05, head[1] == 0x00 else {
            Darwin.close(fd)
            return nil
        }
        let addrLen: Int
        switch head[3] {
        case 0x01:
            addrLen = 4
        case 0x04:
            addrLen = 16
        case 0x03:
            var lenByte: UInt8 = 0
            guard Self.readExact(fd, into: &lenByte, count: 1) else {
                Darwin.close(fd)
                return nil
            }
            addrLen = Int(lenByte)
        default:
            Darwin.close(fd)
            return nil
        }
        var discard = [UInt8](repeating: 0, count: addrLen + 2)
        guard Self.readBytes(fd, into: &discard, count: discard.count) else {
            Darwin.close(fd)
            return nil
        }
        return fd
    }

    private func relayBoth(_ clientFd: Int32, _ backendFd: Int32) {
        let group = DispatchGroup()
        group.enter()
        DispatchQueue.global(qos: .userInitiated).async {
            Self.relayStream(from: clientFd, to: backendFd)
            group.leave()
        }
        group.enter()
        DispatchQueue.global(qos: .userInitiated).async {
            Self.relayStream(from: backendFd, to: clientFd)
            group.leave()
        }
        group.wait()
    }

    private static func relayStream(from src: Int32, to dst: Int32) {
        var buf = [UInt8](repeating: 0, count: 16_384)
        while true {
            let n = Darwin.recv(src, &buf, buf.count, 0)
            if n <= 0 { break }
            if !writeAll(dst, Array(buf[0..<n])) { break }
        }
        Darwin.shutdown(dst, SHUT_WR)
    }

    private static func writeAll(_ fd: Int32, _ bytes: [UInt8]) -> Bool {
        var offset = 0
        while offset < bytes.count {
            let written = bytes.withUnsafeBytes { raw in
                Darwin.send(fd, raw.baseAddress!.advanced(by: offset), bytes.count - offset, 0)
            }
            if written <= 0 { return false }
            offset += written
        }
        return true
    }

    private static func readExact(_ fd: Int32, into byte: inout UInt8, count: Int) -> Bool {
        var buf = [UInt8](repeating: 0, count: count)
        guard readBytes(fd, into: &buf, count: count) else { return false }
        byte = buf[0]
        return true
    }

    private static func readBytes(_ fd: Int32, into buf: inout [UInt8], count: Int) -> Bool {
        var offset = 0
        while offset < count {
            let n = buf.withUnsafeMutableBytes { raw in
                Darwin.recv(fd, raw.baseAddress!.advanced(by: offset), count - offset, 0)
            }
            if n <= 0 { return false }
            offset += n
        }
        return true
    }

    private static func writeHttpResponse(
        _ fd: Int32,
        status: String,
        extraHeaders: [String] = []
    ) -> Bool {
        var text = "HTTP/1.1 \(status)\r\n"
        for header in extraHeaders {
            text += "\(header)\r\n"
        }
        text += "Content-Length: 0\r\nConnection: close\r\n\r\n"
        return writeAll(fd, Array(text.utf8))
    }

    private static func parseHostPort(_ authority: String, defaultPort: Int) -> (String, Int)? {
        let trimmed = authority.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.hasPrefix("[") {
            guard let close = trimmed.firstIndex(of: "]") else { return nil }
            let host = String(trimmed[trimmed.index(after: trimmed.startIndex)..<close])
            guard !host.isEmpty else { return nil }
            let after = String(trimmed[trimmed.index(after: close)...])
            if after.isEmpty { return (host, defaultPort) }
            guard after.hasPrefix(":"), let port = Int(after.dropFirst()), (1...65535).contains(port) else {
                return nil
            }
            return (host, port)
        }
        guard let colon = trimmed.lastIndex(of: ":") else {
            return (trimmed, defaultPort)
        }
        let host = String(trimmed[..<colon])
        guard !host.isEmpty, !host.contains(":") else { return nil }
        guard let port = Int(trimmed[trimmed.index(after: colon)...]), (1...65535).contains(port) else {
            return nil
        }
        return (host, port)
    }

    private static func parseHttpRequestTarget(
        rawTarget: String,
        headers: [String]
    ) -> (host: String, port: Int, path: String, hostHeader: String)? {
        if rawTarget.hasPrefix("/") {
            for line in headers {
                guard let colon = line.firstIndex(of: ":") else { continue }
                let name = line[..<colon].trimmingCharacters(in: .whitespaces)
                guard name.caseInsensitiveCompare("Host") == .orderedSame else { continue }
                let val = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                guard let hp = parseHostPort(val, defaultPort: 80) else { return nil }
                return (hp.0, hp.1, rawTarget, val)
            }
            return nil
        }
        guard rawTarget.lowercased().hasPrefix("http://") else { return nil }
        let rest = String(rawTarget.dropFirst(7))
        let splitIdx = [rest.firstIndex(of: "/"), rest.firstIndex(of: "?")].compactMap { $0 }.min() ?? rest.endIndex
        let authority = String(rest[..<splitIdx])
        let path: String
        if splitIdx == rest.endIndex {
            path = "/"
        } else if rest[splitIdx] == "?" {
            path = "/" + String(rest[splitIdx...])
        } else {
            path = String(rest[splitIdx...])
        }
        guard let hp = parseHostPort(authority, defaultPort: 80) else { return nil }
        return (hp.0, hp.1, path, authority)
    }

    private static func buildForwardedHttpRequest(
        method: String,
        path: String,
        version: String,
        hostHeader: String,
        headers: [String]
    ) -> String {
        var filtered = [String]()
        var hasHost = false
        for line in headers {
            let name = line.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false).first?
                .trimmingCharacters(in: .whitespaces) ?? ""
            if name.caseInsensitiveCompare("Proxy-Authorization") == .orderedSame ||
                name.caseInsensitiveCompare("Proxy-Connection") == .orderedSame ||
                name.caseInsensitiveCompare("Connection") == .orderedSame {
                continue
            }
            if name.caseInsensitiveCompare("Host") == .orderedSame {
                hasHost = true
            }
            filtered.append(line)
        }
        var out = "\(method) \(path) \(version)\r\n"
        if !hasHost {
            out += "Host: \(hostHeader)\r\n"
        }
        for line in filtered {
            out += "\(line)\r\n"
        }
        out += "Connection: close\r\n\r\n"
        return out
    }

    private static func disableSigPipe(_ fd: Int32) {
        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
    }

    private static func posixError(_ message: String, code: Int32) -> NSError {
        NSError(
            domain: NSPOSIXErrorDomain,
            code: Int(code),
            userInfo: [NSLocalizedDescriptionKey: "\(message) (errno=\(code))"]
        )
    }
}
