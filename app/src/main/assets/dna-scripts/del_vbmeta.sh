#!/system/bin/sh
# PDNA canonical paths; DNA_* is accepted only as a legacy fallback.
set -u
PDNA_DIR="${PDNA_DIR:-${DNA_DIR:-/sdcard/PDNA}}"
PDNA_TMP="${PDNA_TMP:-${DNA_TMP:-/data/PDNA}}"
PDNA_PRO="${PDNA_PRO:-}"
PDNA_DRO="${PDNA_DRO:-}"
TMPDIR="${TMPDIR:-/data/local/tmp}"
if [ ! -d $PDNA_DIR/out ];then
  mkdir -p $PDNA_DIR/out
fi
for i in $IMG ;do
 info=$(dna gettype $PDNA_DIR/$i)
 if [ "$info" = "vbmeta" ];then
   echo "> 正在去除$i验证"
   cp -rf $PDNA_DIR/$i $PDNA_DIR/out/$i
   magiskboot hexpatch $PDNA_DIR/out/$i 0000000000000000617662746F6F6C20 0000000200000000617662746F6F6C20
   echo "> 完成，文件位于$PDNA_DIR/out"
 else
   echo "> $i不支持去vbmeta验证！"
   continue
 fi
done