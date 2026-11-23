package links

import (
	"strings"
	"unicode/utf8"
)

var namedCountries = map[string]string{
	"германия":       "DE",
	"germany":        "DE",
	"финляндия":      "FI",
	"finland":        "FI",
	"польша":         "PL",
	"poland":         "PL",
	"нидерланды":     "NL",
	"netherlands":    "NL",
	"франция":        "FR",
	"france":         "FR",
	"швеция":         "SE",
	"sweden":         "SE",
	"латвия":         "LV",
	"latvia":         "LV",
	"эстония":        "EE",
	"estonia":        "EE",
	"литва":          "LT",
	"lithuania":      "LT",
	"великобритания": "GB",
	"uk":             "GB",
	"сша":            "US",
	"usa":            "US",
	"турция":         "TR",
	"turkey":         "TR",
	"казахстан":      "KZ",
	"kazakhstan":     "KZ",
	"сингапур":       "SG",
	"singapore":      "SG",
	"япония":         "JP",
	"japan":          "JP",
}

func CountryOf(label string) string {
	s := strings.TrimSpace(label)
	for {
		r, size := utf8.DecodeRuneInString(s)
		if r >= 0x1F1E6 && r <= 0x1F1FF {
			s = strings.TrimSpace(s[size:])
			continue
		}
		break
	}
	first := s
	if i := strings.IndexByte(first, ' '); i >= 0 {
		first = first[:i]
	}
	if i := strings.IndexByte(first, '|'); i >= 0 {
		first = first[:i]
	}
	first = strings.TrimSpace(first)
	if len(first) == 2 && first[0] >= 'A' && first[0] <= 'Z' && first[1] >= 'A' && first[1] <= 'Z' {
		return first
	}
	if code, ok := namedCountries[strings.ToLower(first)]; ok {
		return code
	}
	return ""
}
