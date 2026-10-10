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
PDNA_TMP="$PDNA_TMP/$project"

systemdir="$PDNA_TMP/system/system"
configdir="$PDNA_TMP/config"
dynamic_fs_dir="$PDNA_TMP/dynamic_fs"
target_fs="$configdir/system_fs_config"
target_contexts="$configdir/system_file_contexts"

rm -rf $PDNA_TMP/dynamic_fs
mkdir -p $PDNA_TMP/dynamic_fs

for partition in $partition_name ;do
  [ ! -d $systemdir ] && continue && echo "system.img未解包，请解包后继续"
  [ ! -d $PDNA_TMP/$partition ] && continue && echo "$partition.img未解包，请解包后继续"
  echo "> 合并 ${partition} 分区"
  if [ -d $PDNA_TMP/$partition ];then
    rm -rf $systemdir/$partition
    rm -rf $PDNA_TMP/$partition/lost+found
    mv $PDNA_TMP/$partition $systemdir/
    rm -rf "$systemdir/../$partition"
    ln -s "/system/$partition" "$systemdir/../$partition"
  fi
  
  if [ -f $configdir/${partition}_file_contexts ];then
    cp -frp $configdir/${partition}_file_contexts $dynamic_fs_dir/
  fi

  if [ -f $configdir/${partition}_fs_config ];then
    cp -frp $configdir/${partition}_fs_config $dynamic_fs_dir/
  fi
    [ ! -d $systemdir/etc/init/config ] && mkdir -p $systemdir/etc/init/config
    [ ! -d $systemdir/system_ext/etc/init/config ] && mkdir -p $systemdir/system_ext/etc/init/config
    echo "# Skip ""system"" mountpoints." >> $systemdir/etc/init/config/skip_mount.cfg
    echo "/oem" >> $systemdir/etc/init/config/skip_mount.cfg
    echo "/product" >> $systemdir/etc/init/config/skip_mount.cfg
    echo "/system_ext" >> $systemdir/etc/init/config/skip_mount.cfg
    echo "# Skip sub-mountpoints of system mountpoints." >> $systemdir/etc/init/config/skip_mount.cfg
    echo "/oem/*" >> $systemdir/etc/init/config/skip_mount.cfg
    echo "/product/*" >> $systemdir/etc/init/config/skip_mount.cfg
    echo "/system_ext/*" >> $systemdir/etc/init/config/skip_mount.cfg
    echo "/system/*" >> $systemdir/etc/init/config/skip_mount.cfg
    echo "# Skip ""system"" mountpoints." >> $systemdir/system_ext/etc/init/config/skip_mount.cfg
    echo "/oem" >> $systemdir/system_ext/etc/init/config/skip_mount.cfg
    echo "/product" >> $systemdir/system_ext/etc/init/config/skip_mount.cfg
    echo "/system_ext" >> $systemdir/system_ext/etc/init/config/skip_mount.cfg
    echo "# Skip sub-mountpoints of system mountpoints." >> $systemdir/system_ext/etc/init/config/skip_mount.cfg
    echo "/oem/*" >> $systemdir/system_ext/etc/init/config/skip_mount.cfg
    echo "/product/*" >> $systemdir/system_ext/etc/init/config/skip_mount.cfg
    echo "/system_ext/*" >> $systemdir/system_ext/etc/init/config/skip_mount.cfg
    echo "/system/*" >> $systemdir/system_ext/etc/init/config/skip_mount.cfg
  for i in $(ls $dynamic_fs_dir | grep "${partition}_file_contexts$");do
    if [ -e $dynamic_fs_dir/$i ];then
      contexts_header=$(grep -n -o -E "^/ u:" $dynamic_fs_dir/$i | head -n 1 | grep -o -E "^[0-9]+")
      [ -n "$contexts_header" ] && sed -i "${contexts_header}d" $dynamic_fs_dir/$i
      sed -i '/\?/d' $dynamic_fs_dir/$i
      sed -i "/system\/${partition} /d" $target_contexts
      echo "/system/${partition} u:object_r:system_file:s0" >> $target_contexts
      sed -i "s/^/&\/system\/system/g" $dynamic_fs_dir/$i
      cat $dynamic_fs_dir/$i >> $dynamic_fs_dir/${partition}_merge_contexts
      cat $dynamic_fs_dir/${partition}_merge_contexts >> $target_contexts
    fi
  done

  for i in $(ls $dynamic_fs_dir | grep "${partition}_fs_config$");do
    if [ -e $dynamic_fs_dir/$i ];then
      config_header=$(grep -n -o -E "^/ 0" $dynamic_fs_dir/$i | head -n 1 | grep -o -E "^[0-9]+")
      [ -n "$config_header" ] && sed -i "${config_header}d" $dynamic_fs_dir/$i
      sed -i "/system\/${partition} /d" $target_fs
      echo "system/${partition} 0 0 0644 /system/${partition}" >> $target_fs      
      sed -i "s/^/&system\/system\//g" $dynamic_fs_dir/$i
      cat $dynamic_fs_dir/$i >> $dynamic_fs_dir/${partition}_merge_fs_config
      cat $dynamic_fs_dir/${partition}_merge_fs_config >> $target_fs
    fi
  done
  echo "分区合并完成"
done