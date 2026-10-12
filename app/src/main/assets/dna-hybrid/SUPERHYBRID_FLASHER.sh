#!/system/bin/sh
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
FASTBOOT="$SCRIPT_DIR/Platform-Tools/fastboot"
[ -x "$FASTBOOT" ] || FASTBOOT=fastboot
echo "================================================"
echo "{{DEVICE_MODEL}} 线卡一体包刷写工具"
echo "移植机型: {{PORT_MODEL}}"
echo "制作者: {{SCRIPT_AUTHOR}}"
echo "================================================"
[ -d "$SCRIPT_DIR/probiotics" ] || { echo "找不到 probiotics 目录"; exit 1; }
for image in "$SCRIPT_DIR"/probiotics/*.img; do
  [ -f "$image" ] || continue
  name=$(basename "$image" .img)
  [ "$name" = super ] && continue
  "$FASTBOOT" flash --slot=all "$name" "$image" || exit 1
done
echo "请确认设备已进入 fastbootd，按回车继续..."
read dummy
[ -f "$SCRIPT_DIR/probiotics/super.img" ] && "$FASTBOOT" flash super "$SCRIPT_DIR/probiotics/super.img"
"$FASTBOOT" set-active a || exit 1
echo "刷写完成。请按需双清数据后重启。"
