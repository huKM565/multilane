package bond

import (
	"context"
	"net"
	"strconv"
	"time"

	"github.com/openlibrecommunity/olcrtc/pkg/bondmux"
)

type bondMuxParams struct {
	TargetAddr   string
	UUID         string
	RoomPorts    []int
	RoomUser     string
	RoomPass     string
	SocksHost    string
	SocksPort    int
	SocksUser    string
	SocksPass    string
	ReadyTimeout time.Duration
	Logf         func(format string, args ...any)
}

var startBondMux = func(ctx context.Context, p bondMuxParams) (xrayEngine, error) {
	cli, err := bondmux.StartClient(ctx, bondmux.ClientConfig{
		ListenAddr:   net.JoinHostPort(p.SocksHost, strconv.Itoa(p.SocksPort)),
		SocksUser:    p.SocksUser,
		SocksPass:    p.SocksPass,
		TargetAddr:   p.TargetAddr,
		UUID:         p.UUID,
		RoomPorts:    p.RoomPorts,
		RoomUser:     p.RoomUser,
		RoomPass:     p.RoomPass,
		ReadyTimeout: p.ReadyTimeout,
		Logf:         p.Logf,
	})
	if err != nil {
		return nil, err
	}
	return &bondmuxEngine{client: cli}, nil
}

type bondmuxEngine struct {
	client *bondmux.Client
}

func (e *bondmuxEngine) SocksAddr() string {
	return e.client.ListenAddr()
}

func (e *bondmuxEngine) Credentials() (string, string) {
	return e.client.Credentials()
}

func (e *bondmuxEngine) State() string {
	if e.client.IsRunning() {
		return "running"
	}
	return "stopped"
}

func (e *bondmuxEngine) Stop(time.Duration) error {
	return e.client.Close()
}
