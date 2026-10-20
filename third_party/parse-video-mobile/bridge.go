package bridge

import (
	"encoding/json"

	"github.com/wujunwei928/parse-video/parser"
)

// ParseShareURL invokes the upstream parser and returns its result as JSON.
// An error is encoded as a JSON object so the Android client can use its legacy fallback.
func ParseShareURL(shareURL string) string {
	info, err := parser.ParseVideoShareUrl(shareURL)
	if err != nil {
		encoded, _ := json.Marshal(map[string]string{"error": err.Error()})
		return string(encoded)
	}
	if info == nil {
		return `{"error":"upstream parser returned empty data"}`
	}
	encoded, err := json.Marshal(info)
	if err != nil {
		encoded, _ = json.Marshal(map[string]string{"error": err.Error()})
	}
	return string(encoded)
}
