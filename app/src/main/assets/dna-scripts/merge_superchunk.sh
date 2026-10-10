#!/system/bin/sh
# PDNA canonical paths; DNA_* is accepted only as a legacy fallback.
set -u
PDNA_DIR="${PDNA_DIR:-${DNA_DIR:-/sdcard/PDNA}}"
PDNA_TMP="${PDNA_TMP:-${DNA_TMP:-/data/PDNA}}"
PDNA_PRO="${PDNA_PRO:-}"
PDNA_DRO="${PDNA_DRO:-}"
TMPDIR="${TMPDIR:-/data/local/tmp}"
project="${PDNA_PROJECT:-}"
[ -n "$project" ] || [ ! -f "$TMPDIR/PDNA.ini" ] || project=$(cat "$TMPDIR/PDNA.ini")
[ -n "$project" ] || [ ! -f "$TMPDIR/DNA.ini" ] || project=$(cat "$TMPDIR/DNA.ini")
PDNA_PRO="${PDNA_PRO:-$PDNA_DIR/$project}"
if [ ! -d $PDNA_PRO/out ];then
 mkdir -p $PDNA_PRO/out
fi

for prefix in $IMG; do
    safe_prefix=$(printf "%s" "$prefix" | sed 's/[.[*^$+(){}|]/\\&/g')
    pattern="^${safe_prefix}\.[0-9]\\{1,\\}$"
    find "$PDNA_PRO" -maxdepth 1 -type f -name "${prefix}*" -exec basename {} \; |
    grep "$pattern" |
    tr '\n' ' ' |
    {
        read -r files
        cd $PDNA_PRO
        echo "> 开始将文件合并到：$prefix"
        simg2img ${files% } $PDNA_PRO/out/$prefix
        cd
        if [ -f $PDNA_PRO/out/$prefix ];then
            echo "> 转换完成，文件位于：$PDNA_PRO/out/$prefix"
        else
            echo "DEBUG: > 处理独立前缀 '$prefix'" >&2
            echo "DEBUG: > 生成正则模式 '$pattern'" >&2
            echo "DEBUG: > 处理'${files% }'失败，请截图联系开发者修复" >&2
        fi
    }
done
