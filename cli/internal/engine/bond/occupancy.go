package bond

import (
	"context"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

type RoomOccupancyProbe func(ctx context.Context, room links.BondRoom) (int, error)

var defaultOccupancyProbe RoomOccupancyProbe = probeJitsiOccupancy

const (
	occupancyProbeTimeout = 4 * time.Second
	occupancyReadTimeout  = 2500 * time.Millisecond
)

type jitsiHostConfig struct {
	xmppDomain string
	mucDomain  string
	wsURL      string
}

var (
	jitsiConfigMu    sync.Mutex
	jitsiConfigCache = map[string]jitsiHostConfig{}
)

func SelectRooms(
	ctx context.Context,
	rooms []links.BondRoom,
	activeCount int,
	probe RoomOccupancyProbe,
	rng *rand.Rand,
	logf func(format string, args ...any),
) []links.BondRoom {
	if len(rooms) == 0 {
		return nil
	}
	want := len(rooms)
	if activeCount > 0 && activeCount < want {
		want = activeCount
	}
	if rng == nil {
		rng = rand.New(rand.NewSource(time.Now().UnixNano()))
	}
	if logf == nil {
		logf = func(string, ...any) {}
	}

	jitsiCount := 0
	for _, r := range rooms {
		if r.Provider == "jitsi" {
			jitsiCount++
		}
	}
	shouldProbe := probe != nil && jitsiCount >= 2 && (want < len(rooms) || len(rooms) > 4)
	if !shouldProbe {
		if want == len(rooms) {
			return append([]links.BondRoom(nil), rooms...)
		}
		perm := rng.Perm(len(rooms))
		out := make([]links.BondRoom, want)
		for i := range want {
			out[i] = rooms[perm[i]]
		}
		return out
	}

	probeCtx, cancel := context.WithTimeout(ctx, occupancyProbeTimeout)
	defer cancel()

	counts := make([]int, len(rooms))
	for i := range counts {
		counts[i] = -1
	}

	var wg sync.WaitGroup
	for i, r := range rooms {
		if r.Provider != "jitsi" {
			continue
		}
		wg.Add(1)
		go func(idx int, room links.BondRoom) {
			defer wg.Done()
			n, err := probe(probeCtx, room)
			if err == nil && n >= 0 {
				counts[idx] = n
			}
		}(i, r)
	}
	wg.Wait()

	type candidate struct {
		idx   int
		count int
		tie   int
	}
	perm := rng.Perm(len(rooms))
	cands := make([]candidate, len(rooms))
	for i := range rooms {
		cands[i] = candidate{idx: i, count: counts[i], tie: perm[i]}
	}
	tier := func(c int) int {
		if c >= 1 {
			return 0
		}
		if c < 0 {
			return 1
		}
		return 2
	}
	sort.Slice(cands, func(i, j int) bool {
		ti, tj := tier(cands[i].count), tier(cands[j].count)
		if ti != tj {
			return ti < tj
		}
		if ti == 0 && cands[i].count != cands[j].count {
			return cands[i].count < cands[j].count
		}
		return cands[i].tie < cands[j].tie
	})

	parts := make([]string, len(rooms))
	for i, c := range counts {
		if c < 0 {
			parts[i] = fmt.Sprintf("r%d=?", i+1)
		} else {
			parts[i] = fmt.Sprintf("r%d=%d", i+1, c)
		}
	}
	chosenLabels := make([]string, want)
	out := make([]links.BondRoom, want)
	for i := range want {
		out[i] = rooms[cands[i].idx]
		chosenLabels[i] = fmt.Sprintf("r%d", cands[i].idx+1)
	}
	logf("bond occupancy: %s -> active %d/%d (%s)", strings.Join(parts, " "), want, len(rooms), strings.Join(chosenLabels, ","))
	return out
}

func parseJitsiRoomURL(raw string) (host, room string, ok bool) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Host == "" {
		return "", "", false
	}
	room = strings.Trim(u.Path, "/")
	if idx := strings.LastIndex(room, "/"); idx >= 0 {
		room = room[idx+1:]
	}
	room = strings.ToLower(strings.TrimSpace(room))
	if room == "" {
		return "", "", false
	}
	return u.Host, room, true
}

func fetchJitsiHostConfig(ctx context.Context, host string, client *http.Client) jitsiHostConfig {
	jitsiConfigMu.Lock()
	defer jitsiConfigMu.Unlock()
	if cfg, ok := jitsiConfigCache[host]; ok {
		return cfg
	}

	cfg := jitsiHostConfig{
		xmppDomain: host,
		mucDomain:  "conference." + host,
		wsURL:      fmt.Sprintf("wss://%s/xmpp-websocket", host),
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://"+host+"/config.js", nil)
	if err == nil {
		resp, doErr := client.Do(req)
		if doErr == nil {
			body, _ := io.ReadAll(io.LimitReader(resp.Body, 256*1024))
			_ = resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				text := string(body)
				if v := extractJSField(text, "domain"); v != "" {
					cfg.xmppDomain = v
				}
				if v := extractJSField(text, "muc"); v != "" {
					cfg.mucDomain = v
				}
				if v := extractJSField(text, "websocket"); v != "" {
					if strings.HasPrefix(v, "//") {
						v = "wss:" + v
					}
					cfg.wsURL = v
				}
			}
		}
	}

	jitsiConfigCache[host] = cfg
	return cfg
}

func extractJSField(body, key string) string {
	for _, line := range strings.Split(body, "\n") {
		t := strings.TrimSpace(line)
		t = strings.TrimPrefix(t, "config.hosts.")
		t = strings.TrimPrefix(t, "config.")
		if !strings.HasPrefix(t, key) {
			continue
		}
		rest := strings.TrimPrefix(t, key)
		if len(rest) == 0 || (rest[0] != ':' && rest[0] != '=' && rest[0] != ' ' && rest[0] != '\t') {
			continue
		}
		rest = strings.TrimLeft(rest, " \t:=")
		if i := strings.IndexAny(rest, ";,"); i >= 0 {
			rest = rest[:i]
		}
		if i := indexJSComment(rest); i >= 0 {
			rest = rest[:i]
		}
		if v := joinJSLiterals(rest); v != "" {
			return v
		}
	}
	return ""
}

func indexJSComment(s string) int {
	var quote byte
	for i := 0; i < len(s); i++ {
		c := s[i]
		if quote != 0 {
			if c == quote {
				quote = 0
			}
			continue
		}
		if c == '\'' || c == '"' {
			quote = c
			continue
		}
		if c == '/' && i+1 < len(s) && s[i+1] == '/' {
			return i
		}
	}
	return -1
}

func joinJSLiterals(expr string) string {
	var b strings.Builder
	for i := 0; i < len(expr); i++ {
		c := expr[i]
		if c != '\'' && c != '"' {
			continue
		}
		end := strings.IndexByte(expr[i+1:], c)
		if end < 0 {
			break
		}
		b.WriteString(expr[i+1 : i+1+end])
		i += 1 + end
	}
	return b.String()
}

func probeJitsiOccupancy(ctx context.Context, room links.BondRoom) (int, error) {
	host, roomName, ok := parseJitsiRoomURL(room.Room)
	if !ok {
		return -1, errors.New("invalid jitsi room url")
	}
	httpClient := &http.Client{Timeout: occupancyReadTimeout}
	cfg := fetchJitsiHostConfig(ctx, host, httpClient)
	return probeXMPPMUCOccupancy(ctx, cfg, host, roomName, httpClient)
}

func probeXMPPMUCOccupancy(
	ctx context.Context,
	cfg jitsiHostConfig,
	originHost string,
	roomName string,
	httpClient *http.Client,
) (int, error) {
	wsURL := cfg.wsURL
	if strings.Contains(wsURL, "?") {
		wsURL += "&room=" + url.QueryEscape(roomName)
	} else {
		wsURL += "?room=" + url.QueryEscape(roomName)
	}
	ws, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		Subprotocols: []string{"xmpp"},
		HTTPClient:   httpClient,
		HTTPHeader: http.Header{
			"Origin": {"https://" + originHost},
		},
	})
	if err != nil {
		return -1, err
	}
	defer func() { _ = ws.Close(websocket.StatusNormalClosure, "") }()

	send := func(stanza string) error {
		return ws.Write(ctx, websocket.MessageText, []byte(stanza))
	}
	recv := func() (string, error) {
		rctx, cancel := context.WithTimeout(ctx, occupancyReadTimeout)
		defer cancel()
		_, data, rerr := ws.Read(rctx)
		return string(data), rerr
	}

	openStanza := fmt.Sprintf(`<open to="%s" version="1.0" xmlns="urn:ietf:params:xml:ns:xmpp-framing"/>`, cfg.xmppDomain)
	if err := send(openStanza); err != nil {
		return -1, err
	}
	for {
		msg, err := recv()
		if err != nil {
			return -1, err
		}
		if strings.Contains(msg, "features") {
			break
		}
	}

	if err := send(`<auth mechanism="ANONYMOUS" xmlns="urn:ietf:params:xml:ns:xmpp-sasl"/>`); err != nil {
		return -1, err
	}
	for {
		msg, err := recv()
		if err != nil {
			return -1, err
		}
		if strings.Contains(msg, "<success") {
			break
		}
		if strings.Contains(msg, "<failure") {
			return -1, errors.New("xmpp anonymous auth rejected")
		}
	}

	if err := send(openStanza); err != nil {
		return -1, err
	}
	for {
		msg, err := recv()
		if err != nil {
			return -1, err
		}
		if strings.Contains(msg, "features") {
			break
		}
	}

	if err := send(`<iq type="set" id="bind_1" xmlns="jabber:client"><bind xmlns="urn:ietf:params:xml:ns:xmpp-bind"/></iq>`); err != nil {
		return -1, err
	}
	nick := ""
	for {
		msg, err := recv()
		if err != nil {
			return -1, err
		}
		if jid := extractXMLTag(msg, "jid"); jid != "" {
			if slash := strings.LastIndex(jid, "/"); slash >= 0 {
				nick = jid[slash+1:]
			} else if at := strings.Index(jid, "@"); at > 0 {
				nick = jid[:at]
			}
			break
		}
	}
	if nick == "" {
		return -1, errors.New("xmpp bind returned no nick")
	}

	roomJID := fmt.Sprintf("%s@%s/%s", roomName, cfg.mucDomain, nick)
	join := fmt.Sprintf(`<presence to="%s" xmlns="jabber:client"><x xmlns="http://jabber.org/protocol/muc"/></presence>`, roomJID)
	if err := send(join); err != nil {
		return -1, err
	}

	occupants := map[string]struct{}{}
	roomPrefix := roomName + "@"
	for {
		msg, err := recv()
		if err != nil {
			return -1, err
		}
		if !strings.HasPrefix(msg, "<presence") {
			continue
		}
		from := extractXMLAttribute(msg, "from")
		if strings.HasPrefix(from, roomPrefix) {
			if slash := strings.LastIndex(from, "/"); slash >= 0 {
				peer := from[slash+1:]
				if peer != "" && peer != "focus" && peer != nick {
					if strings.Contains(msg, `type='unavailable'`) || strings.Contains(msg, `type="unavailable"`) {
						delete(occupants, peer)
					} else {
						occupants[peer] = struct{}{}
					}
				}
			}
		}
		if strings.Contains(msg, `code="110"`) || strings.Contains(msg, `code='110'`) {
			break
		}
	}

	_ = send(fmt.Sprintf(`<presence to="%s" type="unavailable" xmlns="jabber:client"/>`, roomJID))
	return len(occupants), nil
}

func extractXMLTag(s, tag string) string {
	open := "<" + tag + ">"
	i := strings.Index(s, open)
	if i < 0 {
		return ""
	}
	i += len(open)
	end := strings.Index(s[i:], "</"+tag+">")
	if end < 0 {
		return ""
	}
	return s[i : i+end]
}

func extractXMLAttribute(s, attr string) string {
	for _, q := range []string{`"`, `'`} {
		key := attr + "=" + q
		i := strings.Index(s, key)
		if i < 0 {
			continue
		}
		i += len(key)
		end := strings.Index(s[i:], q)
		if end < 0 {
			continue
		}
		return s[i : i+end]
	}
	return ""
}
