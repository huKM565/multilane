package bond

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/xray"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

type fakeBondRuntime struct {
	mu      sync.Mutex
	udp     bool
	port    int
	state   string
	stopped bool
}

func (f *fakeBondRuntime) SetProvider(string) error                 { return nil }
func (f *fakeBondRuntime) SetTransport(string) error                { return nil }
func (f *fakeBondRuntime) SetRoom(string) error                     { return nil }
func (f *fakeBondRuntime) SetKey(string) error                      { return nil }
func (f *fakeBondRuntime) SetDNS(string) error                      { return nil }
func (f *fakeBondRuntime) SetSocksListenHost(string) error          { return nil }
func (f *fakeBondRuntime) SetSocksPort(p int) error                 { f.mu.Lock(); f.port = p; f.mu.Unlock(); return nil }
func (f *fakeBondRuntime) SetSocksCredentials(string, string) error { return nil }
func (f *fakeBondRuntime) SetUDP(v bool)                            { f.udp = v }
func (f *fakeBondRuntime) SetDirectRules(string) error              { return nil }
func (f *fakeBondRuntime) SetDeviceIDPath(string)                   {}
func (f *fakeBondRuntime) SetVP8Options(int, int) error             { return nil }
func (f *fakeBondRuntime) Start() error                             { f.mu.Lock(); f.state = "running"; f.mu.Unlock(); return nil }
func (f *fakeBondRuntime) WaitReady(int) error                      { return nil }
func (f *fakeBondRuntime) Stop(int) error                           { f.mu.Lock(); f.state = "stopped"; f.stopped = true; f.mu.Unlock(); return nil }
func (f *fakeBondRuntime) State() string                            { f.mu.Lock(); defer f.mu.Unlock(); return f.state }

type fakeBondXray struct {
	addr  string
	user  string
	pass  string
	state string
	stops atomic.Int32
}

func (f *fakeBondXray) SocksAddr() string             { return f.addr }
func (f *fakeBondXray) Credentials() (string, string) { return f.user, f.pass }
func (f *fakeBondXray) State() string                 { return f.state }
func (f *fakeBondXray) Stop(time.Duration) error      { f.stops.Add(1); f.state = "stopped"; return nil }

func TestBondRecoversDeadRoomWithoutRestartingXray(t *testing.T) {
	origRT := olcrtc.NewRuntime
	origXR := startBondXray
	t.Cleanup(func() {
		olcrtc.NewRuntime = origRT
		startBondXray = origXR
	})

	var mu sync.Mutex
	var created []*fakeBondRuntime
	olcrtc.NewRuntime = func() olcrtc.Runtime {
		rt := &fakeBondRuntime{}
		mu.Lock()
		created = append(created, rt)
		mu.Unlock()
		return rt
	}

	var xrayStarts atomic.Int32
	var gotPorts []int
	startBondXray = func(_ context.Context, p xray.BondParams) (xrayEngine, error) {
		xrayStarts.Add(1)
		gotPorts = append([]int(nil), p.RoomPorts...)
		return &fakeBondXray{addr: "127.0.0.1:22080", user: p.SocksUser, pass: p.SocksPass, state: "running"}, nil
	}

	vless, err := links.ParseVless("vless://11111111-1111-1111-1111-111111111111@127.0.0.1:10000?security=none&encryption=none#VLESS")
	if err != nil {
		t.Fatal(err)
	}
	key := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	line := links.OlcrtcLine{
		Provider:  "jitsi",
		Transport: "datachannel",
		Room:      "r1",
		Key:       key,
		Label:     "DE Mamba 4x",
		BondVless: vless,
		BondRooms: []links.BondRoom{
			{Provider: "jitsi", Transport: "datachannel", Room: "r1", Key: key},
			{Provider: "jitsi", Transport: "datachannel", Room: "r2", Key: key},
			{Provider: "jitsi", Transport: "datachannel", Room: "r3", Key: key},
			{Provider: "jitsi", Transport: "datachannel", Room: "r4", Key: key},
		},
	}

	bs, err := Start(context.Background(), Params{
		Line:             line,
		DNS:              "1.1.1.1:53",
		ReadyTimeout:     2 * time.Second,
		RecoveryInterval: 30 * time.Millisecond,
		SettleGrace:      20 * time.Millisecond,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = bs.Stop(2 * time.Second) }()

	if xrayStarts.Load() != 1 || len(gotPorts) != 4 {
		t.Fatalf("xrayStarts=%d ports=%v", xrayStarts.Load(), gotPorts)
	}
	mu.Lock()
	for i, rt := range created {
		if rt.udp {
			t.Fatalf("room %d had UDP enabled, want false", i)
		}
	}
	victim := created[1]
	victimPort := victim.port
	mu.Unlock()

	victim.mu.Lock()
	victim.state = "stopped"
	victim.mu.Unlock()

	deadline := time.Now().Add(1 * time.Second)
	var restartedPort int
	for time.Now().Before(deadline) {
		mu.Lock()
		if len(created) >= 5 {
			last := created[len(created)-1]
			last.mu.Lock()
			if last.state == "running" {
				restartedPort = last.port
			}
			last.mu.Unlock()
		}
		mu.Unlock()
		if restartedPort != 0 {
			break
		}
		time.Sleep(15 * time.Millisecond)
	}

	if restartedPort != victimPort {
		t.Fatalf("restarted room port = %d, want %d", restartedPort, victimPort)
	}
	if xrayStarts.Load() != 1 {
		t.Fatalf("xray was restarted %d times, want 1", xrayStarts.Load())
	}
	if bs.State() != "running" {
		t.Fatalf("state = %s, want running", bs.State())
	}
	if up, total := bs.BondRooms(); up != 4 || total != 4 {
		t.Fatalf("BondRooms() = %d/%d, want 4/4", up, total)
	}
}
