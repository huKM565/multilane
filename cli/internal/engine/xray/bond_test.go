package xray

import (
	"bytes"
	"encoding/json"
	"testing"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

func TestBuildBondConfigLoadsInRealXrayCore(t *testing.T) {
	v, err := links.ParseVless("vless://11111111-1111-1111-1111-111111111111@127.0.0.1:10000?security=none&encryption=none#VLESS")
	if err != nil {
		t.Fatal(err)
	}
	raw, err := BuildBondConfig(BondParams{
		Line:          *v,
		RoomPorts:     []int{21081, 21082, 21083, 21084},
		RoomUser:      "ru",
		RoomPass:      "rp",
		SocksHost:     "127.0.0.1",
		SocksPort:     22080,
		SocksUser:     "su",
		SocksPass:     "sp",
		FallbackIndex: 2,
	})
	if err != nil {
		t.Fatal(err)
	}
	var parsed map[string]any
	if err := json.Unmarshal(raw, &parsed); err != nil {
		t.Fatal(err)
	}
	obs := parsed["observatory"].(map[string]any)
	if obs["probeInterval"] != "8s" {
		t.Fatalf("probeInterval = %v, want 8s", obs["probeInterval"])
	}
	routing := parsed["routing"].(map[string]any)
	balancers := routing["balancers"].([]any)
	if len(balancers) != 2 {
		t.Fatalf("balancers len = %d, want 2", len(balancers))
	}
	if balancers[0].(map[string]any)["tag"] != "bond-udp" || balancers[0].(map[string]any)["fallbackTag"] != "vless-room-3" {
		t.Fatalf("unexpected udp balancer: %+v", balancers[0])
	}
	if balancers[1].(map[string]any)["tag"] != "bond" || balancers[1].(map[string]any)["fallbackTag"] != "vless-room-3" {
		t.Fatalf("unexpected tcp balancer: %+v", balancers[1])
	}

	pb, err := serial.LoadJSONConfig(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("serial.LoadJSONConfig: %v", err)
	}
	inst, err := core.New(pb)
	if err != nil {
		t.Fatalf("core.New: %v", err)
	}
	_ = inst.Close()
}
