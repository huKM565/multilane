package links

import "testing"

func TestEntriesParsesBondedSubscriptionMetadata(t *testing.T) {
	key := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	body := []byte(
		"#profile-title: MultiLane\n" +
			"olcrtc://jitsi?datachannel@https://m1.example/r1#" + key + "$Германия — Mamba 4x\n" +
			"##group: Германия\n" +
			"##rooms: https://m2.example/r2 https://m3.example/r3, https://m4.example/r4\n" +
			"##bond-vless: vless://11111111-1111-1111-1111-111111111111@127.0.0.1:10000?security=none&encryption=none#VLESS\n" +
			"##bond-active: 2\n" +
			"olcrtc://vkcalls?vp8channel@https://vk.ru/call/join/a#" + key + "$Польша — VK 2x\n" +
			"##group: Польша\n" +
			"##bond-room: olcrtc://jitsi?datachannel@https://m5.example/r5#" + key + "\n" +
			"##bond-vless: vless://22222222-2222-2222-2222-222222222222@127.0.0.1:10000?security=none&encryption=none#VLESS\n",
	)
	entries := Entries("https://sub.hukm.dev/test", DecodeBody(body))
	if len(entries) != 2 {
		t.Fatalf("got %d entries, want 2: %+v", len(entries), entries)
	}
	e0 := entries[0]
	if !e0.Connectable() || e0.Olcrtc == nil || !e0.Olcrtc.IsBonded() {
		t.Fatalf("entry 0 should be bonded and connectable: %+v", e0)
	}
	if e0.Country != "DE" {
		t.Fatalf("entry 0 country = %q, want DE", e0.Country)
	}
	if len(e0.Olcrtc.BondRooms) != 4 {
		t.Fatalf("entry 0 rooms = %d, want 4", len(e0.Olcrtc.BondRooms))
	}
	if e0.Olcrtc.BondRooms[0].Room != "https://m1.example/r1" || e0.Olcrtc.BondRooms[3].Room != "https://m4.example/r4" {
		t.Fatalf("unexpected room list: %+v", e0.Olcrtc.BondRooms)
	}
	if e0.Olcrtc.BondVless.UUID != "11111111-1111-1111-1111-111111111111" {
		t.Fatalf("unexpected bond vless: %+v", e0.Olcrtc.BondVless)
	}
	if e0.Olcrtc.BondActive != 2 {
		t.Fatalf("entry 0 BondActive = %d, want 2", e0.Olcrtc.BondActive)
	}

	e1 := entries[1]
	if !e1.Connectable() || e1.Olcrtc == nil || !e1.Olcrtc.IsBonded() {
		t.Fatalf("entry 1 should be bonded and connectable: %+v", e1)
	}
	if e1.Country != "PL" {
		t.Fatalf("entry 1 country = %q, want PL", e1.Country)
	}
	if len(e1.Olcrtc.BondRooms) != 2 || e1.Olcrtc.BondRooms[0].Provider != "vkcalls" || e1.Olcrtc.BondRooms[1].Provider != "jitsi" {
		t.Fatalf("unexpected mixed bond rooms: %+v", e1.Olcrtc.BondRooms)
	}
}
