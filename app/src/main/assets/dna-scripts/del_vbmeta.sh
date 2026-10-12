#!/system/bin/sh
# PDNA canonical paths; DNA_* is accepted only as a legacy fallback.
set -u
PDNA_DIR="${PDNA_DIR:-${DNA_DIR:-/sdcard/PDNA}}"
OUT_DIR="$PDNA_DIR/out"
mkdir -p "$OUT_DIR"

for i in ${IMG:-}; do
    [ -n "$i" ] || continue
    src="$PDNA_DIR/$i"
    [ -f "$src" ] || continue
    info="$(dna gettype "$src" 2>/dev/null | tr -d '\r')"
    case "$info" in
        vbmeta*)
            cp -f "$src" "$OUT_DIR/$i" || continue
            magiskboot hexpatch "$OUT_DIR/$i" \
                0000000000000000617662746F6F6C20 \
                0000000200000000617662746F6F6C20 >/dev/null 2>&1 || rm -f "$OUT_DIR/$i"
            ;;
    esac
done
