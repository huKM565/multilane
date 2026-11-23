package links

import (
	"net/url"
	"strings"
)

// ImportPayload unwraps the app's deep links: ghostlane://add?url=<encoded>
// and ghostlane://add/<raw list URL> (also import/). Anything else is returned
// as given.
func ImportPayload(s string) string {
	s = strings.TrimSpace(s)
	lower := strings.ToLower(s)
	var rest string
	switch {
	case strings.HasPrefix(lower, "multilane://"):
		rest = s[len("multilane://"):]
	case strings.HasPrefix(lower, "ghostlane://"):
		rest = s[len("ghostlane://"):]
	default:
		return s
	}
	for _, host := range []string{"add", "import"} {
		if strings.HasPrefix(strings.ToLower(rest), host+"?") {
			q, _ := url.ParseQuery(rest[len(host)+1:])
			if v := q.Get("url"); v != "" {
				return v
			}
			return s
		}
		if strings.HasPrefix(strings.ToLower(rest), host+"/") {
			return rest[len(host)+1:]
		}
	}
	return s
}
