# 需求实施计划

- [x] 1. 修复 Linux rootfs 镜像来源与归档识别
  - [x] 1.1 更新 Debian 13 ARM64 镜像地址与归档命名
    - 修改 `LinuxImages.java`，使用可直接解压为 rootfs 的 Debian 13 ARM64 proot-distro 压缩包
    - 为修正版归档使用独立文件名，避免继续恢复历史 Debian Cloud 磁盘镜像
    - 覆盖设计要求 R1.1、R1.2：镜像必须具备可用的 `/bin/sh` 和 ARM64 动态链接器
  - [x] 1.2 增强 rootfs 启动前校验
    - 校验 shell 文件和动态链接器路径
    - 对不兼容的压缩包返回明确启动错误
    - 覆盖设计要求 R1.3：启动失败需要提供可定位的原因
  - [ ] 1.3 为镜像归档映射编写单元测试
    - 验证 Debian 13 使用修正版归档名
    - 验证 gzip 和 xz 后缀映射保持正确

- [x] 2. 修复 chroot shell 的环境与提示符
  - [x] 2.1 设置 rootfs 内完整 PATH 和初始工作目录
    - 在进入 login shell 前注入 `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`
    - 将 shell 初始目录固定为 `/`
    - 覆盖设计要求 R2.1：rootfs 内的 `id`、`apt` 和其他系统命令可被正常解析
  - [x] 2.2 修正动态 PS1 提示符
    - 生成 `root@${NAME}:${PWD}# ` 格式
    - 读取 `/etc/os-release` 的系统名称
    - 覆盖设计要求 R2.2：初始提示符显示为 `root@Ubuntu 22.04:/#`
  - [ ] 2.3 编写 shell 初始化属性测试
    - 对不同 `NAME`、`VERSION_ID` 和工作目录验证提示符始终包含用户名、系统名、当前目录和 root 标识
    - 覆盖设计属性 P1：提示符格式对所有受支持 rootfs 保持一致

- [x] 3. 修复包管理器命令可用性
  - [x] 3.1 校正包管理器包装脚本的执行环境
    - 确保包装函数调用 rootfs 内真实的 `apt`、`apk`、`dnf`、`yum` 或 `pkg`
    - 保持用户安装包记录逻辑与现有包管理器调用兼容
    - 覆盖设计要求 R3.1：安装命令可以解析 rootfs 内的软件包和仓库配置
  - [ ] 3.2 编写包管理器环境集成测试
    - 使用最小 ARM64 rootfs fixture 验证 `id`、包管理器和记录脚本均从 rootfs PATH 解析
    - 覆盖设计属性 P2：命令解析结果不依赖 Android 宿主 PATH

- [x] 4. 验证应用构建与相关回归
  - [x] 4.1 执行 Java 编译和 Android 资源校验
    - 验证新增 Activity、Manifest、资源和依赖可以完成编译
    - 覆盖设计要求 R4.1：修复集成到现有 Android 应用
  - [x] 4.2 执行 Release 签名构建
    - 使用项目既有 Gradle 和 Android SDK 完成 `:app:assembleRelease`
    - 确认 Release APK 生成并使用现有签名配置
    - 覆盖设计要求 R4.2：可交付 Release 安装包
  - [ ] 4.3 执行 rootfs 启动脚本回归测试
    - 验证 Debian 13、Ubuntu 和 Alpine rootfs 的 shell 启动参数
    - 验证终端退出、后台运行和唤醒锁状态处理

- [ ] 5. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户
