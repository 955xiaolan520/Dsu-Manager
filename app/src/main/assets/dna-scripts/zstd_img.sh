if [ ! -d $PDNA_PRO/out ];then
 mkdir -p $PDNA_PRO/out
fi

for i in $ZST; do
    if [ ! -f "$PDNA_PRO/$i" ]; then
        echo "错误: 文件 $PDNA_PRO/$i 不存在!"
        continue
    fi
    if expr "$i" : '.*\.zst$' >/dev/null || expr "$i" : '.*\.zstd$' >/dev/null; then
        echo "> 开始解压: $i"
        line=$(basename "$i" | sed 's/\.zst$//' | sed 's/\.zstd$//')
        zstd -d -k -f $PDNA_PRO/$i -o $PDNA_PRO/out/$line
        echo "> 解压完成，文件位于：$PDNA_PRO/out/$line"
    fi
    if expr "$i" : '.*\.img$' >/dev/null; then
        echo "> 开始压缩：$i "
        zstd -$level -T4 -f $PDNA_PRO/$i -o $PDNA_PRO/out/$i.zst
        echo "> 压缩完成，文件位于：$PDNA_PRO/out/$i.zst"
    fi
    if [[ $silence = 1 ]]; then
        echo "> 删除: $i"
        rm -rf "$PDNA_PRO/$i"
    fi
done
