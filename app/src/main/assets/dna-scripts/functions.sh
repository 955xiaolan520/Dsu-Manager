TOOLS_DIR="${TOOLS_DIR:-/data/local/pdna-rec-tools}"
P7Z="${P7Z:-$TOOLS_DIR/7z}"
BIN_TAR_ZST="${BIN_TAR_ZST:-$TOOLS_DIR/bin.tar.zst}"
TWRP_DIR="${TWRP_DIR:-/sdcard/PDNA/twrp1}"
PDNA_DIR="${PDNA_DIR:-/sdcard/PDNA}"

_ts() {
  END_TS=$(date +%s); COST=$((END_TS - START_TS))
  printf "⏰ 总用时: %02d:%02d:%02d\n" $((COST/3600)) $((COST%3600/60)) $((COST%60))
}

prepare_bin() {
    echo "[-] 准备 bin 工具..."

    # PDNA·rec Android 版由 APK 的 Kotlin 解压层预先部署 bin，避免依赖 7z/7zz。
    if [ -d "$TWRP_DIR/bin" ]; then
        echo "[√] bin 已由应用解压就绪"
        return 0
    fi

    [ -f "$BIN_TAR_ZST" ] || { echo "[X] 未找到 $BIN_TAR_ZST"; return 1; }
    [ -f "$P7Z" ]         || { echo "[X] 未找到 $P7Z";         return 1; }
    [ -d "$TWRP_DIR" ]    || { echo "[X] TWRP_DIR 不存在: $TWRP_DIR"; return 1; }

    rm -rf "$TWRP_DIR/bin"

    local tmp_ext="/data/local/tmp/.pdna_extract"
    rm -rf "$tmp_ext"
    mkdir -p "$tmp_ext"

    "$P7Z" x "$BIN_TAR_ZST" -y -o"$tmp_ext" 2>&1
    local p7z_rc=$?
    local tar_file="$(find "$tmp_ext" -type f -name '*.tar' -print -quit)"
    if [ $p7z_rc -ne 0 ] || [ -z "$tar_file" ]; then
        echo "[X] 解压 bin.tar.zst 失败（7z 退出码: $p7z_rc）"
        rm -rf "$tmp_ext"; return 1
    fi

    "$P7Z" x "$tar_file" -y -o"$tmp_ext" 2>&1
    local tar_rc=$?
    if [ $tar_rc -ne 0 ]; then
        echo "[X] 展开内部 tar 失败（7z 退出码: $tar_rc）"
        rm -rf "$tmp_ext"; return 1
    fi
    rm -f "$tar_file"

    if [ -d "$tmp_ext/bin" ]; then
        mv "$tmp_ext/bin" "$TWRP_DIR/"
        chmod -R 0755 "$TWRP_DIR/bin"
        echo "[√] bin 已就绪"
    else
        echo "[X] 解压后未找到 bin/ 目录"
        ls -la "$tmp_ext"
        rm -rf "$tmp_ext"
        return 1
    fi

    rm -rf "$tmp_ext"
    return 0
}

scan_images() {
    echo "[-] 扫描镜像..."

    local img_dir="$TWRP_DIR/images"
    [ -d "$img_dir" ] || { echo "[X] 未找到 images 目录: $img_dir"; return 1; }

    PART_LIST=""
    IMG_COUNT=0
    HAS_SUPER=0
    HAS_ROOT=0

    for img in "$img_dir"/*.img; do
        [ -f "$img" ] || continue
        name=$(basename "$img" .img)
        [ "$name" = "super" ] && { HAS_SUPER=1; continue; }
        if [ "$name" = "root_init_boot" ]; then
            HAS_ROOT=1
            ROOT_IMG="root_init_boot"
            continue
        fi
        PART_LIST="$PART_LIST $name"
        IMG_COUNT=$((IMG_COUNT + 1))
    done

    [ -f "$img_dir/super.img.zst" ] && HAS_SUPER=1
    [ -f "$img_dir/super.img" ]     && HAS_SUPER=1

    PART_LIST=$(printf '%s' "$PART_LIST" | xargs)

    echo "[√] 扫描到 $IMG_COUNT 个镜像分区"
    [ -n "$PART_LIST" ] && echo "    分区列表: $PART_LIST"
    [ "$HAS_SUPER" -eq 1 ] && echo "    含 super 分区"
    if [ "$HAS_ROOT" -eq 1 ]; then
        echo "    含 root_init_boot"
    else
        echo "    未扫描到 root_init_boot.img，跳过 Root 权限判定"
    fi

    [ -z "$PART_LIST" ] && [ "$HAS_SUPER" -eq 0 ] && {
        echo "[X] images 目录中没有任何镜像"; return 1; }

    return 0
}

generate_update_binary() {
    local author="${1:-星落人间}"
    local model="${2:-一加13}"
    local version="${3:-PDNA}"
    local port="${4:-}"
    local port_version="${5:-}"

    echo "[-] 生成 update-binary..."
    echo "   作者: $author"
    echo "   机型: $model"

    local display_author="$author"
    if [ "$author" != "星落人间" ]; then
        display_author="${author}（原作者星落人间）"
    fi

    mkdir -p "$TWRP_DIR/META-INF/com/google/android"

    cat > "$TWRP_DIR/META-INF/com/google/android/update-binary" << UPDATEBINEOF
#!/sbin/bash

OUTFD=/proc/self/fd/\$2
ZIPFILE="\$3"
ZIPNAME=\$(basename "\$ZIPFILE" .zip)

ui_print() {
    echo -e "ui_print \$1\nui_print" >>\$OUTFD
}

package_extract_file() {
    ui_print "- 正在刷写分区 \$(basename \$2)……"
    /tmp/bin/android/7zzs x "\$ZIPFILE" "\$1" -so >"\$2_a"
    /tmp/bin/android/7zzs x "\$ZIPFILE" "\$1" -so >"\$2_b"
}

package_extract_super() {
    ui_print "- 正在刷写分区 \$(basename \$2)……"
    /tmp/bin/android/7zzs x "\$ZIPFILE" "\$1" -so | /tmp/bin/android/zstd -c -d > "\$2"
}

[ -d /tmp ] && rm -rf /tmp
mkdir -p /tmp
unzip -q "\$ZIPFILE" bin/android/7zzs -d /tmp
unzip -q "\$ZIPFILE" bin/android/zstd -d /tmp
chmod -R 0755 /tmp

PartitionPath=/dev/block/bootdevice/by-name

ui_print "=========================================="
ui_print "          ${model}全底层一键刷机包          "
ui_print "=========================================="
ui_print "固件名称：\$ZIPNAME"
ui_print "制作者：${display_author}"
ui_print "机型：${model}"
ui_print "移植版本：${version}"
ui_print "移植机型：${port}"
ui_print "版本号：${port_version}"
ui_print "运行环境：TWRP第三方Recovery"
ui_print "=========================================="
ui_print "温馨提示："
ui_print "1.刷机前请备份重要数据，避免数据丢失"
ui_print "2.红色挂载报错属于正常现象，无需处理"
ui_print "3.全程自动执行，请勿手动中断刷机进程"
ui_print "=========================================="
ui_print " "
ui_print "=========== 正式开始底层刷写 ============"
ui_print " "
UPDATEBINEOF

    if [ "$HAS_ROOT" -eq 1 ]; then
        echo "   [+] 检测到 root_init_boot.img，生成 Root 权限选择菜单"
        cat >> "$TWRP_DIR/META-INF/com/google/android/update-binary" << 'ROOTEOF'

GetVolumeKey() {
    while true; do
        key=$(getevent -qlc 1)
        if echo "$key" | grep -q "KEY_VOLUMEUP"; then
            return 0
        elif echo "$key" | grep -q "KEY_VOLUMEDOWN"; then
            return 1
        fi
    done
}

ui_print "=========== 优先选择ROOT权限 ============"
ui_print " "

ui_print "- 是否需要 Root 权限 (ksu)"
ui_print "- 选择无Root 权限 开机后自行root"
ui_print "- 音量【+】按钮：确认刷入"
ui_print "- 音量【-】按钮：跳过刷入"
ui_print "- =========================================="
if GetVolumeKey; then
    ui_print "- 用户选择：刷入Root权限"
    package_extract_file "images/root_init_boot.img" "/dev/block/bootdevice/by-name/init_boot"
    ui_print "- Root 刷入完成！"
else
    ui_print "- 用户选择：跳过Root权限刷入"
    ui_print "- 已保留原版纯净无Root环境"
fi
ui_print "- =========================================="
ROOTEOF
    else
        echo "   [-] 未扫描到 root_init_boot.img，跳过 Root 权限选择"
    fi

    cat >> "$TWRP_DIR/META-INF/com/google/android/update-binary" << UPDATEBINEOF

for partition in $PART_LIST; do
    package_extract_file "images/\$partition.img" "\${PartitionPath}/\$partition"
done
UPDATEBINEOF

    if [ "$HAS_SUPER" -eq 1 ]; then
        cat >> "$TWRP_DIR/META-INF/com/google/android/update-binary" << 'SUPEREOF'

ui_print "=========== 开始刷入Super分区 ============"
package_extract_super "images/super.img.zst" "${PartitionPath}/super"
ui_print "Super分区刷入完毕"
SUPEREOF
    else
        echo "   [-] 未扫描到 super.img，跳过 Super 分区刷写"
    fi

    cat >> "$TWRP_DIR/META-INF/com/google/android/update-binary" << 'TAILEOF'

bootctl set-active-boot-slot 0
ui_print "- 切换A卡槽成功"

dd if=/dev/zero of="${PartitionPath}/frp" bs=4096 conv=sync
ui_print "- 清除谷歌锁完成！"

rm -rf /data/cache/
rm -rf /data/dalvik-cache/
rm -rf /data/system/package_cache/
ui_print "系统缓存清理完成"

ui_print " "
ui_print "=========================================="
ui_print "            全套底层刷写完毕              "
ui_print "=========================================="
ui_print "✅ 所有底层分区刷入成功"
ui_print "✅ 已锁定A系统启动槽位"
ui_print "✅ 谷歌防盗锁已清空"
ui_print "✅ 系统冗余缓存全部清理"
ui_print "=========================================="
ui_print "直接重启设备即可正常开机进系统"
ui_print "遇到异常卡顿可再次双清数据使用"
ui_print "=========================================="
ui_print "- 红色挂载报错请直接忽略"
ui_print "- 红色挂载报错请直接忽略"
ui_print "- 红色挂载报错请直接忽略"
ui_print "- =========================================="

TAILEOF

    chmod 0755 "$TWRP_DIR/META-INF/com/google/android/update-binary"
    echo "[√] update-binary 生成完成"
    return 0
}

build_zip() {
    START_TS=$(date +%s)
    local prefix="${1:-pdna_update}"
    local zip_name="${prefix}.zip"
    local zip_path="$PDNA_DIR/$zip_name"

    echo "[-] 打包中..."

    [ -d "$TWRP_DIR" ] || { echo "[X] TWRP_DIR 不存在"; return 1; }
    [ -f "$TWRP_DIR/META-INF/com/google/android/update-binary" ] || { echo "[X] update-binary 缺失"; return 1; }
    [ -d "$TWRP_DIR/bin" ] || { echo "[X] bin 目录缺失"; return 1; }

    cd "$TWRP_DIR" || { echo "[X] 无法进入 TWRP_DIR"; return 1; }

    "$P7Z" a -tzip "$zip_path" . -bsp1 2>&1 | grep -vE '^[[:space:]]*[0-9]+%'
    local ret=${PIPESTATUS[0]:-$?}

    cd / || exit 1

    if [ $ret -ne 0 ] || [ ! -f "$zip_path" ]; then
        echo "[X] 打包失败"
        return 1
    fi

    echo "[√] 打包完成: $zip_path"
    _ts
    return 0
}

clean_twrp() {
    echo "[-] 清空 TWRP 目录..."
    [ -d "$TWRP_DIR" ] || { echo "[.] TWRP_DIR 不存在，跳过"; return 0; }
    find "$TWRP_DIR" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null
    echo "[√] TWRP 目录已清空"
    return 0
}

build_flashable_zip() {
    local custom_name="${1:-pdna_update}"
    local clean_mode="${2:-clean}"
    local custom_author="${3:-星落人间}"
    local custom_model="${4:-一加13}"
    local custom_version="${5:-PDNA}"
    local custom_port="${6:-}"
    local custom_port_version="${7:-}"

    echo ""
    echo "=========================================="
    echo "     [星落人间] 卡刷脚本构建开始 可卡刷可adb sideload"
    echo "=========================================="
    echo ""
    echo "   文件名: $custom_name"
    echo "   清空: $clean_mode"
    echo "   作者: $custom_author"
    echo "   机型: $custom_model"
    echo ""

    prepare_bin || { echo "[X] bin 准备失败"; return 1; }
    scan_images           || { echo "[X] 扫描镜像失败"; return 1; }
    generate_update_binary "$custom_author" "$custom_model" "$custom_version" "$custom_port" "$custom_port_version" || { echo "[X] 生成 update-binary 失败"; return 1; }
    build_zip "$custom_name" || { echo "[X] 打包失败"; return 1; }

    if [ "$clean_mode" = "clean" ]; then
        clean_twrp
    else
        echo "[.] 跳过清空 twrp/"
    fi

    echo ""
    echo "=========================================="
    echo "   ✅ [原作者星落人间]卡刷包构建完成"
    echo "   📁保存目录: $PDNA_DIR"
    echo "=========================================="
    echo ""

    return 0
}

debug() {
    echo "===== debug ====="
    echo "参数个数: $#"
    local i=0
    for a in "$@"; do echo "  [$i]=[$a]"; i=$((i+1)); done
    echo "===== end ====="
}

"$@"
