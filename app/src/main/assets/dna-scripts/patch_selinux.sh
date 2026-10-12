if [ ! -d "$PDNA_DIR/out/permissive" ];then
    mkdir -p $PDNA_DIR/out/permissive
fi
cd $PDNA_DIR/out/permissive
for i in $IMG ;do
    line=$(echo "$i" | cut -d"." -f1)
    echo   "> 开始分解 ${i} 分区"
    magiskboot unpack -h $PDNA_DIR/$i &>/dev/null
    echo   "> 开始插入宽容代码"
    header="$PDNA_DIR/out/permissive/header"
    search_cmdline_index=$(grep -n "^cmdline=" $header | cut -d ":" -f 1)
    sed -i "s/androidboot.selinux=enforcing/androidboot.selinux=permissive/" $header
    sed -i "s/androidboot.selinux=permissive//g" $header
    sed -i "/^cmdline=/{s/$/& androidboot.selinux=permissive/}" $header
    sed -i -e 's;  *; ;g' -e 's;[ \t]*$;;' $header
    magiskboot repack $PDNA_DIR/$i &>/dev/null
    echo   "> 开始合并 ${i} 分区"
    mv $PDNA_DIR/out/permissive/new-boot.img $PDNA_DIR/out/vendor_boot.img
    echo   "> 宽容完成，文件位于$PDNA_DIR/out/vendor_boot.img"
    rm -rf $PDNA_DIR/out/permissive
done
