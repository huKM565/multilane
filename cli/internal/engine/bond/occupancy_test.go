package bond

import (
	"context"
	"errors"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

func makeJitsiRooms(n int) []links.BondRoom {
	rooms := make([]links.BondRoom, n)
	key := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	for i := range n {
		rooms[i] = links.BondRoom{
			Provider:  "jitsi",
			Transport: "datachannel",
			Room:      "https://meet.example.invalid/room-" + string(rune('1'+i)),
			Key:       key,
		}
	}
	return rooms
}

func TestSelectRoomsPicksLeastOccupiedAndAvoidsEmpty(t *testing.T) {
	rooms := makeJitsiRooms(8)
	occ := map[string]int{
		rooms[0].Room: 5,
		rooms[1].Room: 1,
		rooms[2].Room: 0,
		rooms[3].Room: 2,
		rooms[4].Room: 4,
		rooms[5].Room: 1,
		rooms[6].Room: 3,
		rooms[7].Room: 0,
	}
	probe := func(_ context.Context, r links.BondRoom) (int, error) {
		return occ[r.Room], nil
	}

	var logged string
	got := SelectRooms(context.Background(), rooms, 4, probe, rand.New(rand.NewSource(42)), func(f string, args ...any) {
		logged = f
	})
	if len(got) != 4 {
		t.Fatalf("got %d rooms, want 4", len(got))
	}
	wantSet := map[string]bool{
		rooms[1].Room: true,
		rooms[5].Room: true,
		rooms[3].Room: true,
		rooms[6].Room: true,
	}
	for _, r := range got {
		if !wantSet[r.Room] {
			t.Fatalf("unexpected room selected: %s (got=%+v)", r.Room, got)
		}
	}
	if logged == "" {
		t.Fatal("expected occupancy log line")
	}
}

func TestSelectRoomsRandomTieBreakSpreadsSelections(t *testing.T) {
	rooms := makeJitsiRooms(8)
	probe := func(_ context.Context, _ links.BondRoom) (int, error) {
		return 1, nil
	}
	seenFirst := map[string]bool{}
	for seed := int64(1); seed <= 20; seed++ {
		got := SelectRooms(context.Background(), rooms, 4, probe, rand.New(rand.NewSource(seed)), nil)
		if len(got) != 4 {
			t.Fatalf("seed %d: got %d rooms", seed, len(got))
		}
		seenFirst[got[0].Room] = true
	}
	if len(seenFirst) < 3 {
		t.Fatalf("tie-breaking did not spread selections: %v", seenFirst)
	}
}

func TestSelectRoomsFallsBackWhenProbeFails(t *testing.T) {
	rooms := makeJitsiRooms(8)
	probe := func(_ context.Context, _ links.BondRoom) (int, error) {
		return -1, errors.New("network error")
	}
	got := SelectRooms(context.Background(), rooms, 4, probe, rand.New(rand.NewSource(7)), nil)
	if len(got) != 4 {
		t.Fatalf("got %d rooms, want 4", len(got))
	}
}

func TestProbeXMPPMUCOccupancyCountsOnlyOtherParticipants(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{
			Subprotocols:       []string{"xmpp"},
			InsecureSkipVerify: true,
		})
		if err != nil {
			return
		}
		defer func() { _ = ws.Close(websocket.StatusNormalClosure, "") }()
		ctx := r.Context()
		read := func() string {
			_, data, _ := ws.Read(ctx)
			return string(data)
		}
		write := func(s string) {
			_ = ws.Write(ctx, websocket.MessageText, []byte(s))
		}
		_ = read()
		write(`<open xmlns="urn:ietf:params:xml:ns:xmpp-framing"/>`)
		write(`<stream:features><mechanisms xmlns="urn:ietf:params:xml:ns:xmpp-sasl"><mechanism>ANONYMOUS</mechanism></mechanisms></stream:features>`)
		_ = read()
		write(`<success xmlns="urn:ietf:params:xml:ns:xmpp-sasl"/>`)
		_ = read()
		write(`<stream:features><bind xmlns="urn:ietf:params:xml:ns:xmpp-bind"/></stream:features>`)
		_ = read()
		write(`<iq type="result" id="bind_1"><bind xmlns="urn:ietf:params:xml:ns:xmpp-bind"><jid>u@meet.test/mynick</jid></bind></iq>`)
		_ = read()
		write(`<presence from="room1@conference.meet.test/focus"><x xmlns="http://jabber.org/protocol/muc#user"/></presence>`)
		write(`<presence from="room1@conference.meet.test/srv001"><x xmlns="http://jabber.org/protocol/muc#user"/></presence>`)
		write(`<presence from="room1@conference.meet.test/cli002"><x xmlns="http://jabber.org/protocol/muc#user"/></presence>`)
		write(`<presence from="room1@conference.meet.test/mynick"><x xmlns="http://jabber.org/protocol/muc#user"><status code="110"/></x></presence>`)
		_ = read()
	}))
	defer srv.Close()

	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http") + "/xmpp-websocket"
	cfg := jitsiHostConfig{
		xmppDomain: "meet.test",
		mucDomain:  "conference.meet.test",
		wsURL:      wsURL,
	}
	count, err := probeXMPPMUCOccupancy(context.Background(), cfg, "meet.test", "room1", srv.Client())
	if err != nil {
		t.Fatalf("probeXMPPMUCOccupancy error: %v", err)
	}
	if count != 2 {
		t.Fatalf("count = %d, want 2", count)
	}
}

func TestLiveSubscriptionOccupancy(t *testing.T) {
	path := os.Getenv("MULTILANE_LIVE_SUB")
	if path == "" {
		t.Skip("MULTILANE_LIVE_SUB not set")
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	entries := links.Entries("https://sub.hukm.dev/local", links.DecodeBody(body))
	probed := false
	for _, e := range entries {
		if e.Olcrtc != nil && len(e.Olcrtc.BondRooms) >= 8 {
			t0 := time.Now()
			chosen := SelectRooms(context.Background(), e.Olcrtc.BondRooms, e.Olcrtc.BondActive, defaultOccupancyProbe, nil, func(f string, args ...any) {
				t.Logf(f, args...)
			})
			t.Logf("country=%s total=%d active=%d chosen=%d elapsed=%v", e.Country, len(e.Olcrtc.BondRooms), e.Olcrtc.BondActive, len(chosen), time.Since(t0).Round(time.Millisecond))
			probed = true
		}
	}
	if !probed {
		t.Fatal("no 8+ room entry found")
	}
}
