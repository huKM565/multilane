package bond

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/xray"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

type Params struct {
	Line             links.OlcrtcLine
	DNS              string
	DeviceIDPath     string
	ReadyTimeout     time.Duration
	RecoveryInterval time.Duration
	SettleGrace      time.Duration
	Logf             func(format string, args ...any)
}

type xrayEngine interface {
	SocksAddr() string
	Credentials() (string, string)
	State() string
	Stop(time.Duration) error
}

var startBondXray = func(ctx context.Context, p xray.BondParams) (xrayEngine, error) {
	return xray.StartBond(ctx, p)
}

type Session struct {
	mu               sync.Mutex
	rooms            []links.BondRoom
	ports            []int
	sessions         []*olcrtc.Session
	roomUser         string
	roomPass         string
	dns              string
	deviceIDPath     string
	readyTimeout     time.Duration
	recoveryInterval time.Duration
	logf             func(format string, args ...any)
	xr               xrayEngine
	stopOnce         sync.Once
	stopped          bool
	cancel           context.CancelFunc
	recoverDone      chan struct{}
}

func Start(ctx context.Context, p Params) (*Session, error) {
	if !p.Line.IsBonded() {
		return nil, errors.New("olcrtc bond: line is not bonded")
	}
	rooms := p.Line.BondRooms
	if len(rooms) < 1 || len(rooms) > 12 {
		return nil, fmt.Errorf("olcrtc bond: expected 1..12 rooms, got %d", len(rooms))
	}
	readyTimeout := p.ReadyTimeout
	if readyTimeout <= 0 {
		readyTimeout = 60 * time.Second
	}
	recoveryInterval := p.RecoveryInterval
	if recoveryInterval <= 0 {
		recoveryInterval = 15 * time.Second
	}
	settleGrace := p.SettleGrace
	if settleGrace <= 0 {
		settleGrace = 2 * time.Second
	}
	logf := p.Logf
	if logf == nil {
		logf = func(string, ...any) {}
	}

	ports, err := allocateDistinctPorts(len(rooms))
	if err != nil {
		return nil, fmt.Errorf("olcrtc bond ports: %w", err)
	}
	xrayPort, err := allocateDistinctPortExcluding(ports)
	if err != nil {
		return nil, fmt.Errorf("olcrtc bond xray port: %w", err)
	}
	roomUser, roomPass := olcrtc.RandomCredentials()
	xrayUser, xrayPass := olcrtc.RandomCredentials()

	type roomResult struct {
		idx     int
		session *olcrtc.Session
		err     error
	}
	results := make(chan roomResult, len(rooms))
	startCtx, cancelStart := context.WithCancel(ctx)
	for i, room := range rooms {
		go func(idx int, r links.BondRoom, port int) {
			s, startErr := olcrtc.Start(startCtx, olcrtc.Params{
				Line: links.OlcrtcLine{
					Provider:  r.Provider,
					Transport: r.Transport,
					Room:      r.Room,
					Key:       r.Key,
					Label:     fmt.Sprintf("%s#%d", p.Line.Label, idx+1),
					VP8FPS:    r.VP8FPS,
					VP8Batch:  r.VP8Batch,
				},
				SocksHost:    "127.0.0.1",
				SocksPort:    port,
				SocksUser:    roomUser,
				SocksPass:    roomPass,
				DNS:          p.DNS,
				DirectRules:  olcrtc.PrivateDirectRules,
				DeviceIDPath: p.DeviceIDPath,
				ReadyTimeout: readyTimeout,
				DisableUDP:   true,
			})
			results <- roomResult{idx: idx, session: s, err: startErr}
		}(i, room, ports[i])
	}

	sessions := make([]*olcrtc.Session, len(rooms))
	completed := 0
	firstReady := -1
	var lastErr error
	var graceTimer *time.Timer
	var graceC <-chan time.Time

	for completed < len(rooms) {
		select {
		case res := <-results:
			completed++
			if res.err != nil {
				lastErr = res.err
				logf("bond room %d/%d failed: %v", res.idx+1, len(rooms), res.err)
			} else {
				sessions[res.idx] = res.session
				if firstReady < 0 {
					firstReady = res.idx
					if completed < len(rooms) {
						graceTimer = time.NewTimer(settleGrace)
						graceC = graceTimer.C
					}
				}
			}
		case <-graceC:
			goto launchXray
		case <-ctx.Done():
			cancelStart()
			if graceTimer != nil {
				graceTimer.Stop()
			}
			stopRoomSessions(sessions, 5*time.Second)
			return nil, ctx.Err()
		}
	}

launchXray:
	if graceTimer != nil {
		graceTimer.Stop()
	}
	if firstReady < 0 {
		cancelStart()
		if lastErr == nil {
			lastErr = errors.New("no bonded rooms became ready")
		}
		return nil, fmt.Errorf("olcrtc bond: %w", lastErr)
	}

	xr, err := startBondXray(ctx, xray.BondParams{
		Line:          *p.Line.BondVless,
		RoomPorts:     ports,
		RoomUser:      roomUser,
		RoomPass:      roomPass,
		SocksHost:     "127.0.0.1",
		SocksPort:     xrayPort,
		SocksUser:     xrayUser,
		SocksPass:     xrayPass,
		FallbackIndex: firstReady,
	})
	if err != nil {
		cancelStart()
		stopRoomSessions(sessions, 5*time.Second)
		return nil, fmt.Errorf("olcrtc bond xray: %w", err)
	}

	bgCtx, bgCancel := context.WithCancel(context.Background())
	bs := &Session{
		rooms:            append([]links.BondRoom(nil), rooms...),
		ports:            ports,
		sessions:         sessions,
		roomUser:         roomUser,
		roomPass:         roomPass,
		dns:              p.DNS,
		deviceIDPath:     p.DeviceIDPath,
		readyTimeout:     readyTimeout,
		recoveryInterval: recoveryInterval,
		logf:             logf,
		xr:               xr,
		cancel: func() {
			cancelStart()
			bgCancel()
		},
		recoverDone: make(chan struct{}),
	}

	remaining := len(rooms) - completed
	go func() {
		defer close(bs.recoverDone)
		for i := 0; i < remaining; i++ {
			select {
			case res := <-results:
				if res.err != nil {
					logf("bond room %d/%d failed: %v", res.idx+1, len(rooms), res.err)
					continue
				}
				bs.mu.Lock()
				if bs.stopped {
					bs.mu.Unlock()
					_ = res.session.Stop(3 * time.Second)
					continue
				}
				bs.sessions[res.idx] = res.session
				bs.mu.Unlock()
			case <-bgCtx.Done():
				return
			}
		}
		bs.recoveryLoop(bgCtx)
	}()

	return bs, nil
}

func (b *Session) recoveryLoop(ctx context.Context) {
	t := time.NewTicker(b.recoveryInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			b.recoverDeadRooms(ctx)
		}
	}
}

func (b *Session) recoverDeadRooms(ctx context.Context) {
	for i := range b.rooms {
		if ctx.Err() != nil {
			return
		}
		b.mu.Lock()
		if b.stopped {
			b.mu.Unlock()
			return
		}
		s := b.sessions[i]
		alive := s != nil && s.State() == "running"
		b.mu.Unlock()
		if alive {
			continue
		}
		if s != nil {
			_ = s.Stop(3 * time.Second)
		}
		room := b.rooms[i]
		fresh, err := olcrtc.Start(ctx, olcrtc.Params{
			Line: links.OlcrtcLine{
				Provider:  room.Provider,
				Transport: room.Transport,
				Room:      room.Room,
				Key:       room.Key,
				VP8FPS:    room.VP8FPS,
				VP8Batch:  room.VP8Batch,
			},
			SocksHost:    "127.0.0.1",
			SocksPort:    b.ports[i],
			SocksUser:    b.roomUser,
			SocksPass:    b.roomPass,
			DNS:          b.dns,
			DirectRules:  olcrtc.PrivateDirectRules,
			DeviceIDPath: b.deviceIDPath,
			ReadyTimeout: b.readyTimeout,
			DisableUDP:   true,
		})
		if err != nil {
			if ctx.Err() == nil {
				b.logf("bond room %d/%d recovery failed: %v", i+1, len(b.rooms), err)
			}
			continue
		}
		b.mu.Lock()
		if b.stopped {
			b.mu.Unlock()
			_ = fresh.Stop(3 * time.Second)
			return
		}
		b.sessions[i] = fresh
		b.mu.Unlock()
		b.logf("bond room %d/%d recovered on 127.0.0.1:%d", i+1, len(b.rooms), b.ports[i])
	}
}

func (b *Session) SocksAddr() string {
	return b.xr.SocksAddr()
}

func (b *Session) Credentials() (string, string) {
	return b.xr.Credentials()
}

func (b *Session) State() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.stopped || b.xr == nil || b.xr.State() != "running" {
		return "stopped"
	}
	for _, s := range b.sessions {
		if s != nil && s.State() == "running" {
			return "running"
		}
	}
	return "stopped"
}

func (b *Session) Stop(timeout time.Duration) error {
	var firstErr error
	b.stopOnce.Do(func() {
		b.mu.Lock()
		b.stopped = true
		sessions := append([]*olcrtc.Session(nil), b.sessions...)
		b.mu.Unlock()

		if b.cancel != nil {
			b.cancel()
		}
		if b.xr != nil {
			if err := b.xr.Stop(timeout); err != nil && firstErr == nil {
				firstErr = err
			}
		}
		stopRoomSessions(sessions, timeout)
		<-b.recoverDone
	})
	return firstErr
}

func stopRoomSessions(sessions []*olcrtc.Session, timeout time.Duration) {
	var wg sync.WaitGroup
	for _, s := range sessions {
		if s == nil {
			continue
		}
		wg.Add(1)
		go func(sess *olcrtc.Session) {
			defer wg.Done()
			_ = sess.Stop(timeout)
		}(s)
	}
	wg.Wait()
}

func allocateDistinctPorts(n int) ([]int, error) {
	used := map[int]bool{}
	ports := make([]int, 0, n)
	for len(ports) < n {
		p, err := olcrtc.FreePort()
		if err != nil {
			return nil, err
		}
		if used[p] {
			continue
		}
		used[p] = true
		ports = append(ports, p)
	}
	return ports, nil
}

func allocateDistinctPortExcluding(existing []int) (int, error) {
	used := map[int]bool{}
	for _, p := range existing {
		used[p] = true
	}
	for i := 0; i < 64; i++ {
		p, err := olcrtc.FreePort()
		if err != nil {
			return 0, err
		}
		if !used[p] {
			return p, nil
		}
	}
	return 0, errors.New("failed to allocate distinct port")
}
