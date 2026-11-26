package xray

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

// maxEachPostBytes is XrayConfig.XHTTP_MAX_EACH_POST_BYTES in the app: the
// upload chunk of packet-up, bounding what Xray buffers.
const maxEachPostBytes = 200_000

var errNotXhttp = errors.New("xray: only xhttp lines run here; Reality tcp and hysteria2 are sing-box's")

type Params struct {
	Line      links.VlessLine
	SocksHost string
	SocksPort int
	SocksUser string
	SocksPass string
	LogLevel  string
}

// BuildConfig is XrayConfig.buildXhttp without the routing and DNS a phone
// needs: a loopback SOCKS in, one vless+xhttp outbound out.
func BuildConfig(p Params) ([]byte, error) {
	l := p.Line
	if l.Transport.Kind != "xhttp" {
		return nil, errNotXhttp
	}
	level := p.LogLevel
	if level == "" {
		level = "warning"
	}
	stream := map[string]any{
		"network":       "xhttp",
		"xhttpSettings": map[string]any{"path": l.Transport.Path, "host": l.Transport.Host, "mode": l.Transport.Mode, "scMaxEachPostBytes": maxEachPostBytes},
	}
	switch {
	case l.Plain:
		stream["security"] = "none"
	case l.PublicKey == "":
		stream["security"] = "tls"
		stream["tlsSettings"] = map[string]any{"serverName": l.SNI, "fingerprint": l.Fingerprint, "allowInsecure": false}
	default:
		stream["security"] = "reality"
		stream["realitySettings"] = map[string]any{"serverName": l.SNI, "fingerprint": l.Fingerprint, "publicKey": l.PublicKey, "shortId": l.ShortID}
	}
	cfg := map[string]any{
		"log": map[string]any{"loglevel": level},
		"inbounds": []map[string]any{{
			"tag": "in", "listen": p.SocksHost, "port": p.SocksPort, "protocol": "socks",
			"settings": map[string]any{"auth": "password", "accounts": []map[string]any{{"user": p.SocksUser, "pass": p.SocksPass}}, "udp": true},
		}},
		"outbounds": []map[string]any{{
			"tag": "out", "protocol": "vless",
			"settings":       map[string]any{"vnext": []map[string]any{{"address": l.Host, "port": l.Port, "users": []map[string]any{{"id": l.UUID, "encryption": "none"}}}}},
			"streamSettings": stream,
		}},
	}
	return json.Marshal(cfg)
}

type BondParams struct {
	Line          links.VlessLine
	RoomPorts     []int
	RoomUser      string
	RoomPass      string
	SocksHost     string
	SocksPort     int
	SocksUser     string
	SocksPass     string
	Costs         []float64
	FallbackIndex int
	LogLevel      string
}

func BuildBondConfig(p BondParams) ([]byte, error) {
	if len(p.RoomPorts) < 1 || len(p.RoomPorts) > 16 {
		return nil, fmt.Errorf("xray bond: expected 1..16 room ports, got %d", len(p.RoomPorts))
	}
	seenPorts := map[int]bool{}
	for _, port := range p.RoomPorts {
		if port < 1 || port > 65535 || seenPorts[port] {
			return nil, fmt.Errorf("xray bond: invalid or duplicate room port %d", port)
		}
		seenPorts[port] = true
	}
	if !p.Line.Plain || p.Line.Transport.Kind != "tcp" {
		return nil, errors.New("xray bond: requires plain TCP VLESS endpoint")
	}
	fallbackIdx := p.FallbackIndex
	if fallbackIdx < 0 || fallbackIdx >= len(p.RoomPorts) {
		return nil, fmt.Errorf("xray bond: fallback index %d out of range", fallbackIdx)
	}
	costs := p.Costs
	if len(costs) == 0 {
		costs = make([]float64, len(p.RoomPorts))
		for i := range costs {
			costs[i] = 1.0
		}
	}
	if len(costs) != len(p.RoomPorts) {
		return nil, fmt.Errorf("xray bond: costs count %d does not match rooms %d", len(costs), len(p.RoomPorts))
	}
	for _, c := range costs {
		if math.IsNaN(c) || math.IsInf(c, 0) || c <= 0 {
			return nil, fmt.Errorf("xray bond: invalid cost %v", c)
		}
	}
	level := p.LogLevel
	if level == "" {
		level = "warning"
	}
	inSettings := map[string]any{"udp": true}
	if p.SocksUser != "" || p.SocksPass != "" {
		inSettings["auth"] = "password"
		inSettings["accounts"] = []map[string]any{{"user": p.SocksUser, "pass": p.SocksPass}}
	}

	outbounds := make([]map[string]any, 0, len(p.RoomPorts)*2)
	selector := make([]string, 0, len(p.RoomPorts))
	costEntries := make([]map[string]any, 0, len(p.RoomPorts))
	for i := range p.RoomPorts {
		n := i + 1
		vtag := fmt.Sprintf("vless-room-%d", n)
		stag := fmt.Sprintf("socks-room-%d", n)
		selector = append(selector, vtag)
		costEntries = append(costEntries, map[string]any{
			"regexp": false,
			"match":  vtag,
			"value":  costs[i],
		})
		outbounds = append(outbounds, map[string]any{
			"tag":      vtag,
			"protocol": "vless",
			"settings": map[string]any{
				"vnext": []map[string]any{{
					"address": p.Line.Host,
					"port":    p.Line.Port,
					"users":   []map[string]any{{"id": p.Line.UUID, "encryption": "none"}},
				}},
			},
			"streamSettings": map[string]any{
				"network":  "tcp",
				"security": "none",
				"sockopt":  map[string]any{"dialerProxy": stag},
			},
			"mux": map[string]any{
				"enabled":         true,
				"concurrency":     -1,
				"xudpConcurrency": 16,
				"xudpProxyUDP443": "allow",
			},
		})
	}
	for i, port := range p.RoomPorts {
		srv := map[string]any{"address": "127.0.0.1", "port": port}
		if p.RoomUser != "" || p.RoomPass != "" {
			srv["users"] = []map[string]any{{"user": p.RoomUser, "pass": p.RoomPass}}
		}
		outbounds = append(outbounds, map[string]any{
			"tag":      fmt.Sprintf("socks-room-%d", i+1),
			"protocol": "socks",
			"settings": map[string]any{"servers": []map[string]any{srv}},
		})
	}

	fallbackTag := fmt.Sprintf("vless-room-%d", fallbackIdx+1)
	expected := len(p.RoomPorts)
	cfg := map[string]any{
		"log": map[string]any{"loglevel": level},
		"inbounds": []map[string]any{{
			"tag": "in", "listen": p.SocksHost, "port": p.SocksPort,
			"protocol": "socks", "settings": inSettings,
		}},
		"outbounds": outbounds,
		"routing": map[string]any{
			"domainStrategy": "AsIs",
			"rules": []map[string]any{
				{"type": "field", "inboundTag": []string{"in"}, "network": "udp", "balancerTag": "bond-udp"},
				{"type": "field", "inboundTag": []string{"in"}, "balancerTag": "bond"},
			},
			"balancers": []map[string]any{
				{
					"tag":         "bond-udp",
					"selector":    selector,
					"fallbackTag": fallbackTag,
					"strategy":    map[string]any{"type": "leastPing"},
				},
				{
					"tag":         "bond",
					"selector":    selector,
					"fallbackTag": fallbackTag,
					"strategy": map[string]any{
						"type": "leastLoad",
						"settings": map[string]any{
							"expected":  expected,
							"maxRTT":    "5s",
							"tolerance": 0.20,
							"baselines": []string{"500ms"},
							"costs":     costEntries,
						},
					},
				},
			},
		},
		"observatory": map[string]any{
			"subjectSelector":   selector,
			"probeUrl":          "http://cp.cloudflare.com/generate_204",
			"probeInterval":     "30s",
			"enableConcurrency": false,
		},
	}
	return json.Marshal(cfg)
}
