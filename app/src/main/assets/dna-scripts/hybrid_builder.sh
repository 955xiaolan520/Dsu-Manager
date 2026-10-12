TOOLS_DIR="${TOOLS_DIR:-/data/local/pdna-rec-tools}"
P7Z="${P7Z:-$TOOLS_DIR/7z}"
TWRP_TAR_ZST="${TWRP_TAR_ZST:-$TOOLS_DIR/twrp.tar.zst}"
TWRP_DIR="${TWRP_DIR:-/sdcard/PDNA/twrp}"
PDNA_DIR="${PDNA_DIR:-/sdcard/PDNA}"

VER_NAME="${BUILD_VER_NAME:-ColorOs16.0.0.9}"
DEVICE_MODEL="${BUILD_DEVICE_MODEL:-Oneplus 13}"
PORT_MODEL="${BUILD_PORT_MODEL:-OPPO find X9 ultra 卫星通信版}"
SCRIPT_AUTHOR="${BUILD_SCRIPT_AUTHOR:-小布丁}"
PORT_VERSION="${BUILD_PORT_VERSION:-PMA120_16.0.9.402(CN01)}"

_ts() {
  END_TS=$(date +%s); COST=$((END_TS - START_TS))
  printf "⏰ 总用时: %02d:%02d:%02d\n" $((COST/3600)) $((COST%3600/60)) $((COST%60))
}

extract_twrp() {
    # PDNA·rec Android 版由 APK 的 Kotlin 解压层预先部署 META-INF/Platform-Tools。
    if [ -d "$TWRP_DIR/META-INF" ]; then
        echo "[√] 复用已解压的 TWRP 模板"
        rm -f "$TWRP_DIR/Regional_Flasher_COS.bat"
        mkdir -p "$TWRP_DIR/probiotics"
        return 0
    fi

    echo "[-] 首次提取 twrp.tar.zst ..."

    [ -f "$TWRP_TAR_ZST" ] || { echo "[X] 未找到 $TWRP_TAR_ZST"; return 1; }
    [ -f "$P7Z" ]          || { echo "[X] 未找到 $P7Z";         return 1; }
    mkdir -p "$TWRP_DIR"

    local tmp_ext="/data/local/tmp/.hybrid_extract"
    rm -rf "$tmp_ext"
    mkdir -p "$tmp_ext"

    "$P7Z" x "$TWRP_TAR_ZST" -y -o"$tmp_ext" 2>&1
    local p7z_rc=$?
    local tar_file="$(find "$tmp_ext" -type f -name '*.tar' -print -quit)"
    if [ $p7z_rc -ne 0 ] || [ -z "$tar_file" ]; then
        echo "[X] 解压 twrp.tar.zst 失败（7z 退出码: $p7z_rc）"
        rm -rf "$tmp_ext"; return 1
    fi

    "$P7Z" x "$tar_file" -y -o"$tmp_ext" 2>&1
    local tar_rc=$?
    if [ $tar_rc -ne 0 ]; then
        echo "[X] 展开内部 tar 失败（7z 退出码: $tar_rc）"
        rm -rf "$tmp_ext"; return 1
    fi
    rm -f "$tar_file"

    local meta_dir="$(find "$tmp_ext" -type d -name META-INF -print -quit)"
    [ -n "$meta_dir" ] || { echo "[X] 解压后未找到 META-INF 目录"; rm -rf "$tmp_ext"; return 1; }
    local template_root="${meta_dir%/META-INF}"
    mkdir -p "$TWRP_DIR/probiotics"
    rm -rf "$TWRP_DIR/META-INF" "$TWRP_DIR/Platform-Tools"
    cp -r "$template_root/." "$TWRP_DIR/"

    if [ ! -d "$TWRP_DIR/META-INF/com/google/android/helpers" ] || [ ! -f "$TWRP_DIR/META-INF/com/google/android/update-binary" ]; then
        echo "[X] TWRP 模板缺少 META-INF/helpers 或 update-binary"; rm -rf "$tmp_ext"; return 1
    fi

    rm -rf "$tmp_ext"
    chmod -R 0755 "$TWRP_DIR/META-INF" "$TWRP_DIR/Platform-Tools" 2>/dev/null
    echo "[√] META-INF + Platform-Tools 已就绪"
    return 0
}

scan_images() {
    echo "[-] 扫描镜像..."

    local img_dir="$TWRP_DIR/probiotics"
    [ -d "$img_dir" ] || {
        echo "[X] 未找到 probiotics 目录: $img_dir"
        return 1
    }

    BOOT_LIST=""
    SMALL_LIST=""
    HAS_SUPER=0
    HAS_ROOT=0
    ROOT_IMG=""
    IMG_COUNT=0

    for img in "$img_dir"/*.img; do
        [ -f "$img" ] || continue

        name=$(basename "$img" .img)
        IMG_COUNT=$((IMG_COUNT + 1))

        case "$name" in
            super)
                HAS_SUPER=1
                ;;

            root_init_boot)
                HAS_ROOT=1
                ROOT_IMG="root_init_boot"
                BOOT_LIST="$BOOT_LIST $name"
                ;;

            boot|dtbo|init_boot|recovery|vbmeta|vbmeta_system|vbmeta_vendor|vendor_boot)
                BOOT_LIST="$BOOT_LIST $name"
                ;;

            *)
                SMALL_LIST="$SMALL_LIST $name"
                ;;
        esac
    done

    BOOT_LIST=$(printf '%s' "$BOOT_LIST" | xargs)
    SMALL_LIST=$(printf '%s' "$SMALL_LIST" | xargs)

    echo "[√] 扫描到 $IMG_COUNT 个镜像文件"
    [ -n "$BOOT_LIST" ] && echo "[√] boot 类: $BOOT_LIST"
    [ -n "$SMALL_LIST" ] && echo "[√] 小分区: $SMALL_LIST"
    [ "$HAS_SUPER" -eq 1 ] && echo "[√] 含 super"
    [ "$HAS_ROOT" -eq 1 ] && echo "[√] 含 root_init_boot"

    if [ "$IMG_COUNT" -eq 0 ]; then
        echo "[X] probiotics 中没有镜像"
        return 1
    fi

    return 0
}

generate_updater_script() {
    echo "[-] 生成 updater-script..."

    mkdir -p "$TWRP_DIR/META-INF/com/google/android"

    local raw_author="$SCRIPT_AUTHOR"
    local author_name="$raw_author"
    if echo "$raw_author" | grep -q '（'; then
        author_name="${raw_author%%（*}"
    fi
    local final_author="${author_name}（感谢类原生作者制作的框架写的脚本）"

    cat > "$TWRP_DIR/META-INF/com/google/android/updater-script" << SCRIPTEOF
ui_print(" ");
ui_print("╔══════════════════════════════════════════════════════╗");
ui_print("║  移植版本： ${VER_NAME}                             ║");
ui_print("╠══════════════════════════════════════════════════════╣");
ui_print("║  本机机型： ${DEVICE_MODEL}                         ║");
ui_print("║  移植机型： ${PORT_MODEL}                          ║");
ui_print("║  脚本作者： ${final_author}    ║");
ui_print("║  移植版本： ${PORT_VERSION}                        ║");
ui_print("╠══════════════════════════════════════════════════════╣");
ui_print("║  ⚠ 注意：该ROM默认自带橙狐rec，默认狐狸面具         ║");
ui_print("║  Ksu用户请刷回官方init_boot或替换Ksu修补版          ║");
ui_print("╚══════════════════════════════════════════════════════╝");
ui_print("#");
ui_print("#");

ui_print("############################");
ui_print("# 设置活动槽位到 A #");
ui_print("############################");
run_program("/system/bin/bootctl", "set-active-boot-slot", "0");
ui_print("#");

ui_print("############################");
ui_print("# 正刷入底层 boot #");
ui_print("############################");
SCRIPTEOF

    for part in $BOOT_LIST; do
        cat >> "$TWRP_DIR/META-INF/com/google/android/updater-script" << PARTEOF

ui_print("正在刷入 ${part}.img 到 AB 分区上 等一下噻 不要猴急");
package_extract_file("probiotics/${part}.img", "/dev/block/by-name/${part}_a");
package_extract_file("probiotics/${part}.img", "/dev/block/by-name/${part}_b");
PARTEOF
    done

    cat >> "$TWRP_DIR/META-INF/com/google/android/updater-script" << 'SMALLEOF'

ui_print("#");
ui_print("################################");
ui_print("# 正刷入底层小分区 #");
ui_print("################################");
SMALLEOF

for part in $SMALL_LIST; do
        cat >> "$TWRP_DIR/META-INF/com/google/android/updater-script" << PARTEOF

ui_print("正在刷入 ${part}.img 到 AB 分区上 等一下噻 不要猴急");
package_extract_file("probiotics/${part}.img", "/dev/block/by-name/${part}_a");
package_extract_file("probiotics/${part}.img", "/dev/block/by-name/${part}_b");
PARTEOF
    done

    if [ "$HAS_SUPER" -eq 1 ]; then
        cat >> "$TWRP_DIR/META-INF/com/google/android/updater-script" << 'SUPEOF'

ui_print("#");
ui_print("#");

show_progress(0.700000, 0);

ui_print("############################");
ui_print("# Flashing super partition #");
ui_print("############################");

ui_print("正在刷入 super.img 到 super 分区上 等一下噻 不要猴急");
package_extract_file("probiotics/super.img", "/sdcard/super.img");
run_program("/system/bin/simg2img", "/sdcard/super.img", "/dev/block/by-name/super");
run_program("/system/bin/rm", "-f", "/sdcard/super.img");

set_progress(0.886667);
SUPEOF
    fi

    cat >> "$TWRP_DIR/META-INF/com/google/android/updater-script" << TAILEOF

ui_print("#");
ui_print("#");
ui_print("╔══════════════════════════════════════════════════════╗");
ui_print("║  移植版本： ${VER_NAME}                             ║");
ui_print("╠══════════════════════════════════════════════════════╣");
ui_print("║  本机机型： ${DEVICE_MODEL}                         ║");
ui_print("║  移植机型： ${PORT_MODEL}                          ║");
ui_print("║  脚本作者： ${final_author}    ║");
ui_print("║  移植版本： ${PORT_VERSION}                        ║");
ui_print("╠══════════════════════════════════════════════════════╣");
ui_print("║  ⚠ 注意：该ROM默认自带橙狐rec，默认狐狸面具         ║");
ui_print("║  Ksu用户请刷回官方init_boot或替换Ksu修补版          ║");
ui_print("╚══════════════════════════════════════════════════════╝");
ui_print("╔══════════════════════════════════════════════════════╗");
ui_print("║              ✅ 全套底层刷写完毕 ✅                  ║");
ui_print("╠══════════════════════════════════════════════════════╣");
ui_print("║  直接重启设备即可正常开机进系统                      ║");
ui_print("║  遇到异常卡顿可再次双清数据使用                      ║");
ui_print("╠══════════════════════════════════════════════════════╣");
ui_print("║  ⚠ 红色挂载报错请直接忽略                           ║");
ui_print("╚══════════════════════════════════════════════════════╝");
ui_print("#");

set_progress(1.000000);

TAILEOF

    chmod 0755 "$TWRP_DIR/META-INF/com/google/android/updater-script"
    echo "[√] updater-script 生成完成"
    return 0
}

generate_bat() {
    echo "[-] Generating SUPERHYBRID_FLASHER.bat ..."

    local bat_path="$TWRP_DIR/SUPERHYBRID_FLASHER.bat"
    rm -f "$TWRP_DIR/Regional_Flasher_COS.bat"

    cat > "$bat_path" << BATEOF
@echo off
setlocal
color 2F
title ${DEVICE_MODEL} Hybrid Flasher

echo.**********************************************************************
echo.
echo.         ${DEVICE_MODEL} - Hybrid Flasher
echo.       移植版本: ${VER_NAME} / ${PORT_VERSION}
echo.       移植机型: ${PORT_MODEL}
echo.       作者: ${SCRIPT_AUTHOR}
echo.

cd %~dp0
set fastboot=Platform-Tools\fastboot.exe

if not exist "%fastboot%" (
    echo.
    echo ------------------------------------------------------------
    echo ERROR: "%fastboot%" not found.
    echo ------------------------------------------------------------
    pause
    exit /B 1
)

if not exist "probiotics\*.img" (
    echo.
    echo ------------------------------------------------------------
    echo ERROR: No IMG files found in probiotics.
    echo ------------------------------------------------------------
    pause
    exit /B 1
)

echo.
echo ------------------------------------------------------------
echo START FLASH
echo ------------------------------------------------------------
echo.
echo ------------------------------------------------------------
echo FLASHING BOOT PARTITIONS IN FASTBOOT
echo ------------------------------------------------------------
BATEOF

    for part in $BOOT_LIST; do
        cat >> "$bat_path" << PARTEOF

echo Flashing ${part}.img in fastboot mode...
"%fastboot%" flash "${part}" "probiotics\\${part}.img"
if errorlevel 1 (
    echo.
    echo ------------------------------------------------------------
    echo ERROR: Failed to flash ${part}.img in fastboot mode.
    echo ------------------------------------------------------------
    echo Do not boot into the system.
    pause
    exit /B 1
)
PARTEOF
    done

    cat >> "$bat_path" << 'BATEOF'

echo.
echo ------------------------------------------------------------
echo REBOOTING TO FASTBOOTD
echo ------------------------------------------------------------
"%fastboot%" reboot fastboot
if errorlevel 1 (
    echo.
    echo ------------------------------------------------------------
    echo ERROR: Failed to reboot into fastbootd.
    echo ------------------------------------------------------------
    pause
    exit /B 1
)

echo.
echo ------------------------------------------------------------
echo Please confirm that the device has entered fastbootd mode.
echo Check the device screen before continuing.
echo Press any key to continue flashing.
echo ------------------------------------------------------------
pause

echo.
echo ------------------------------------------------------------
echo FLASHING SCANNED SMALL PARTITIONS IN FASTBOOTD
echo ------------------------------------------------------------

BATEOF

    for part in $SMALL_LIST; do
        cat >> "$bat_path" << PARTEOF

echo Flashing ${part}.img to slots A and B in fastbootd...
"%fastboot%" flash --slot=all "${part}" "probiotics\\${part}.img"
if errorlevel 1 (
    echo ERROR: Failed to flash ${part}.img in fastbootd.
    echo Do not boot if errors appear.
    pause
    exit /B 1
)
PARTEOF
    done

    if [ "$HAS_SUPER" -eq 1 ]; then
        cat >> "$bat_path" << 'SUPEOF'
echo.
echo ------------------------------------------------------------
echo FLASHING SUPER PARTITION
echo ------------------------------------------------------------
echo Flashing super.img...
"%fastboot%" flash super "probiotics\super.img"
if errorlevel 1 (
    echo.
    echo ------------------------------------------------------------
    echo ERROR: Failed to flash super.img.
    echo ------------------------------------------------------------
    echo Do not boot into the system.
    pause
    exit /B 1
)
SUPEOF
    fi

    cat >> "$bat_path" << 'BATEOF'

echo.
echo ------------------------------------------------------------
echo SETTING ACTIVE SLOT A
echo ------------------------------------------------------------
"%fastboot%" set-active a
if errorlevel 1 (
    echo.
    echo ------------------------------------------------------------
    echo ERROR: Failed to set active slot A.
    echo ------------------------------------------------------------
    pause
    exit /B 1
)

echo.
echo ------------------------------------------------------------
echo CHECK ABOVE FOR ERRORS
echo DO NOT BOOT IF ERRORS APPEAR
echo ------------------------------------------------------------

echo.
echo ------------------------------------------------------------
echo FLASHING COMPLETE
echo ------------------------------------------------------------
echo Wipe data in recovery, then perform a factory reset.
echo.
echo Press any key to exit...
endlocal
pause
BATEOF

    echo "[OK] SUPERHYBRID_FLASHER.bat generated"
    return 0
}

generate_sh_flasher() {
    echo "[-] 生成 SUPERHYBRID_FLASHER.sh ..."

    cat > "$TWRP_DIR/SUPERHYBRID_FLASHER.sh" << SHEOF
#!/system/bin/sh

echo "**********************************************************************"
echo ""
echo "         ${DEVICE_MODEL} - SUPERHYBRID FLASHER"
echo "       ${PORT_MODEL}"
echo "          Packaged by ${SCRIPT_AUTHOR}"
echo ""
echo "**********************************************************************"

SCRIPT_DIR="\$(cd "\$(dirname "\$0")" && pwd)"
FASTBOOT="\${SCRIPT_DIR}/Platform-Tools/fastboot"

[ ! -x "\$FASTBOOT" ] && FASTBOOT="fastboot"

if [ ! -d "\${SCRIPT_DIR}/probiotics" ]; then
    echo "ERROR: probiotics directory not found."
    exit 1
fi

echo ""
echo "**************** FLASHING BOOT CLASS IN FASTBOOT ****************"
SHEOF

for part in $BOOT_LIST; do
        cat >> "$TWRP_DIR/SUPERHYBRID_FLASHER.sh" << PARTEOF
echo "Flashing ${part}.img to slot A..."
"\$FASTBOOT" flash "${part}_a" "\${SCRIPT_DIR}/probiotics/${part}.img"
if [ \$? -ne 0 ]; then
    echo "ERROR: Failed to flash ${part}_a."
    echo "Do not boot into the system."
    exit 1
fi

echo "Flashing ${part}.img to slot B..."
"\$FASTBOOT" flash "${part}_b" "\${SCRIPT_DIR}/probiotics/${part}.img"
if [ \$? -ne 0 ]; then
    echo "ERROR: Failed to flash ${part}_b."
    echo "Do not boot into the system."
    exit 1
fi
PARTEOF
    done

    cat >> "$TWRP_DIR/SUPERHYBRID_FLASHER.sh" << 'SHEOF'

echo ""
echo "**************** REBOOTING TO FASTBOOTD ****************"
"$FASTBOOT" reboot fastboot
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to reboot into fastbootd."
    exit 1
fi

echo ""
echo "#####################################"
echo "# Make sure it says fastbootd       #"
echo "# at the top of the device screen.  #"
echo "#####################################"
printf "Press Enter after device enters fastbootd..."
read dummy

echo ""
echo "**************** FLASHING SMALL PARTITIONS IN FASTBOOTD ****************"
SHEOF

    for part in $SMALL_LIST; do
        cat >> "$TWRP_DIR/SUPERHYBRID_FLASHER.sh" << PARTEOF
echo "Flashing ${part}.img to slots A and B..."
"\$FASTBOOT" flash --slot=all "${part}" "\${SCRIPT_DIR}/probiotics/${part}.img"
if [ \$? -ne 0 ]; then
    echo "ERROR: Failed to flash ${part}.img."
    echo "Do not boot into the system."
    exit 1
fi
PARTEOF
    done

    if [ "$HAS_SUPER" -eq 1 ]; then
        cat >> "$TWRP_DIR/SUPERHYBRID_FLASHER.sh" << 'SUPEOF'
echo ""
echo "Flashing super.img..."
"$FASTBOOT" flash super "${SCRIPT_DIR}/probiotics/super.img"
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to flash super.img."
    echo "Do not boot into the system."
    exit 1
fi
SUPEOF
    fi

    cat >> "$TWRP_DIR/SUPERHYBRID_FLASHER.sh" << 'SHEOF'

echo ""
echo "********************** SET ACTIVE SLOT A ******************************"
"$FASTBOOT" set-active a
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to set active slot A."
    exit 1
fi

echo ""
echo "****************** FLASHING COMPLETE *****************"
echo "Wipe data in recovery, then Factory reset"
SHEOF

    chmod 0755 "$TWRP_DIR/SUPERHYBRID_FLASHER.sh"
    echo "[√] SUPERHYBRID_FLASHER.sh 生成完成"
    return 0
}

build_zip() {
    START_TS=$(date +%s)
    local prefix="$1"
    local zip_name="${prefix}.zip"
    local zip_path="$PDNA_DIR/$zip_name"

    echo "[-] 打包中..."

    [ -z "$prefix" ] && { echo "[X] 未指定文件名，不打包"; return 1; }
    [ -d "$TWRP_DIR" ] || { echo "[X] TWRP_DIR 不存在"; return 1; }
    [ -f "$TWRP_DIR/META-INF/com/google/android/updater-script" ] || { echo "[X] updater-script 缺失"; return 1; }

    cd "$TWRP_DIR" || { echo "[X] 无法进入 TWRP_DIR"; return 1; }

    "$P7Z" a -tzip "$zip_path" . -bsp1 2>&1 | grep -vE '^[[:space:]]*[0-9]+%'
    local ret=${PIPESTATUS[0]:-$?}

    cd / || exit 1

    if [ $ret -ne 0 ] || [ ! -f "$zip_path" ]; then
        echo "[X] 打包失败"; return 1
    fi

    echo "[√] 打包完成: $zip_path"
    _ts
    return 0
}

clean_twrp() {
    echo "[-] 清空 hybrid 目录..."
    [ -d "$TWRP_DIR" ] || { echo "[.] 不存在，跳过"; return 0; }
    find "$TWRP_DIR" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null
    echo "[√] 已清空"
    return 0
}

generate_all_scripts() {
    echo "[-] 准备并生成全部刷机脚本..."
    extract_twrp           || { echo "[X] 提取 twrp 失败"; return 1; }
    scan_images             || { echo "[X] 扫描镜像失败"; return 1; }
    generate_updater_script || { echo "[X] 生成 updater-script 失败"; return 1; }
    generate_bat            || { echo "[X] 生成 bat 失败"; return 1; }
    generate_sh_flasher     || { echo "[X] 生成 sh 失败"; return 1; }
    [ -s "$TWRP_DIR/META-INF/com/google/android/updater-script" ] || { echo "[X] updater-script 为空"; return 1; }
    [ -s "$TWRP_DIR/SUPERHYBRID_FLASHER.bat" ] || { echo "[X] bat 脚本为空"; return 1; }
    [ -s "$TWRP_DIR/SUPERHYBRID_FLASHER.sh" ] || { echo "[X] sh 刷机脚本为空"; return 1; }
    echo "[√] 卡刷、bat 线刷、sh 线刷脚本均已按镜像目录生成"
    return 0
}

build_hybrid() {
    local custom_name="${BUILD_CUSTOM_NAME:-${1:-hybrid_update}}"
    local clean_mode="${BUILD_CLEAN_MODE:-${2:-clean}}"
    local tmp_ver="${BUILD_VER_NAME:-$3}"
    local tmp_device="${BUILD_DEVICE_MODEL:-$4}"
    local tmp_port="${BUILD_PORT_MODEL:-$5}"
    local tmp_author="${BUILD_SCRIPT_AUTHOR:-$6}"
    local tmp_portver="${BUILD_PORT_VERSION:-$7}"

    [ -z "$custom_name" ] && { echo "[X] 必须指定文件名才能打包"; return 1; }

    [ -n "$tmp_ver" ]     && VER_NAME="$tmp_ver"
    [ -n "$tmp_device" ]  && DEVICE_MODEL="$tmp_device"
    [ -n "$tmp_port" ]    && PORT_MODEL="$tmp_port"
    [ -n "$tmp_author" ]  && SCRIPT_AUTHOR="$tmp_author"
    [ -n "$tmp_portver" ] && PORT_VERSION="$tmp_portver"

    echo ""
    echo "=========================================="
    echo "   线卡一体脚本构建 可线可卡可adb sideload"
    echo "=========================================="
    echo ""
    echo "   文件名: $custom_name"
    echo "   移植版本: $VER_NAME"
    echo "   本机机型: $DEVICE_MODEL"
    echo "   移植机型: $PORT_MODEL"
    echo "   脚本作者: $SCRIPT_AUTHOR"
    echo "   版本号: $PORT_VERSION"
    echo ""

    mkdir -p "$TWRP_DIR/probiotics"

    extract_twrp            || { echo "[X] 提取 twrp 失败"; return 1; }
    scan_images             || { echo "[X] 扫描镜像失败"; return 1; }
    generate_updater_script || { echo "[X] 生成 updater-script 失败"; return 1; }
    generate_bat            || { echo "[X] 生成 bat 失败"; return 1; }
    generate_sh_flasher     || { echo "[X] 生成 sh 失败"; return 1; }
    build_zip "$custom_name" || { echo "[X] 打包失败"; return 1; }

    if [ "$clean_mode" = "clean" ]; then
        clean_twrp
    else
        echo "[.] 跳过清空"
    fi

    echo ""
    echo "=========================================="
    echo "   ✅ [原作者 小布丁 线卡一体脚本]构建完成"
    echo "   📁保存目录: $PDNA_DIR/${custom_name}.zip"
    echo "=========================================="
    echo ""

    return 0
}

debug() {
    echo "===== debug ====="
    echo "argc=$#"
    local i=0
    for a in "$@"; do echo "  [$i]=[$a]"; i=$((i+1)); done
    echo "===== end ====="
}

"$@"
