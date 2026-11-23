package singbox

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestBuildConfigLoadsDirectRulesFile(t *testing.T) {
	dir := t.TempDir()
	rulesPath := filepath.Join(dir, "direct.rules")
	content := "# comment\ndomain:ru\nfull:ya.ru\n217.198.160.0/20\n"
	if err := os.WriteFile(rulesPath, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	raw, err := BuildConfig(FrontParams{
		Mode:            ModeProxy,
		Upstream:        Upstream{Socks: &SocksUpstream{Addr: "127.0.0.1:22080", User: "u", Pass: "p"}},
		ProxyListen:     "127.0.0.1",
		ProxyPort:       10808,
		DirectRulesFile: rulesPath,
	})
	if err != nil {
		t.Fatal(err)
	}
	var cfg map[string]any
	if err := json.Unmarshal(raw, &cfg); err != nil {
		t.Fatal(err)
	}
	dnsRules := cfg["dns"].(map[string]any)["rules"].([]any)
	if len(dnsRules) != 1 {
		t.Fatalf("dns rules = %d, want 1", len(dnsRules))
	}
	routeRules := cfg["route"].(map[string]any)["rules"].([]any)
	if len(routeRules) != 6 {
		t.Fatalf("route rules = %d, want 6 (4 base + domain + cidr)", len(routeRules))
	}
}
