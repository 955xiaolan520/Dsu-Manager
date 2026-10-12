#!/system/bin/sh
# PDNA canonical paths; DNA_* is accepted only as a legacy fallback.
set -u
PDNA_DIR="${PDNA_DIR:-${DNA_DIR:-/sdcard/PDNA}}"
OUT_DIR="$PDNA_DIR/out"
WORK_DIR="$OUT_DIR/.selinux-work"
mkdir -p "$OUT_DIR"

for i in ${IMG:-}; do
    [ -n "$i" ] || continue
    src="$PDNA_DIR/$i"
    [ -f "$src" ] || continue
    type="$(dna gettype "$src" 2>/dev/null | tr -d '\r')"
    case "$type" in
        boot|vendor_boot) ;;
        *) continue ;;
    esac

    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR"
    cd "$WORK_DIR" || continue
    magiskboot unpack -h "$src" >/dev/null 2>&1 || { rm -rf "$WORK_DIR"; continue; }
    header="$WORK_DIR/header"
    [ -f "$header" ] || { rm -rf "$WORK_DIR"; continue; }
    if grep -q '^cmdline=' "$header"; then
        sed -i 's/androidboot\.selinux=[^ ]*/androidboot.selinux=permissive/g' "$header"
        if ! grep -q 'androidboot\.selinux=permissive' "$header"; then
            sed -i '/^cmdline=/ s/$/ androidboot.selinux=permissive/' "$header"
        fi
    else
        printf '%s\n' 'cmdline=androidboot.selinux=permissive' >> "$header"
    fi
    magiskboot repack "$src" >/dev/null 2>&1 || { rm -rf "$WORK_DIR"; continue; }
    [ -f "$WORK_DIR/new-boot.img" ] && mv -f "$WORK_DIR/new-boot.img" "$OUT_DIR/$i"
    rm -rf "$WORK_DIR"
done
