# Requirements Document

## Introduction

Dsu 管理器需要接入 OPlus-Tracker 的官方 ColorOS OTA 查询能力，为 OPPO、一加和真我设备提供按地区查询、版本展示和 ROM 下载。

## Glossary

- **OPlus OTA**: OPPO、一加和真我 ColorOS/OxygenOS 官方更新服务。
- **设备代号**: OPlus OTA 服务使用的型号前缀，例如 `PJX110` 或 `RMX5200`。
- **地区**: OPlus OTA 服务支持的区域代码。
- **动态下载链接**: OTA 服务返回的带有效期下载地址。

## Requirements

### Requirement 1: Region Selection

**User Story:** 作为 ROM 用户，我希望选择查询地区，以便获取对应地区的 OTA 版本。

#### Acceptance Criteria

1. WHEN 用户打开 OPlus 查询页，系统 SHALL 提供“通用地区（自动匹配全部地区）”选项和上游源码 `OTA_REGION_CONFIG` 中的 `cn`、`cn_cmcc`、`in`、`eu`、`sg`、`ru`、`tr`、`th`、`gl`、`tw`、`my`、`vn`、`id`、`sa`、`mea`、`ph`、`la`、`br` 和 `roe` 地区选项，其中 `gl` SHALL 显示为“全球海外 GL”。
2. WHEN 用户选择地区，系统 SHALL 显示地区中文名称和地区代码。
3. WHEN 用户提交查询，系统 SHALL 使用当前选择的地区参数请求 OTA 服务。
4. WHEN 用户选择通用地区，系统 SHALL 依次尝试全部公开地区配置，并为每个成功结果标记实际匹配地区。

### Requirement 2: OTA Query

**User Story:** 作为 ROM 用户，我希望输入设备代号查询官方更新，以便获取最新系统包。

#### Acceptance Criteria

1. WHEN 用户输入合法设备代号并提交查询，系统 SHALL 请求 OPlus 官方 OTA 接口。
2. WHEN OTA 服务返回成功结果，系统 SHALL 展示版本号、OTA 版本、发布时间、安全补丁、更新说明和可用组件。
3. WHEN OTA 服务返回多个组件，系统 SHALL 分别展示组件名称、版本、大小、MD5 和下载操作。
4. IF 查询失败，系统 SHALL 展示可读的错误状态并保留设备代号和地区选择。

### Requirement 3: OTA Request Security

**User Story:** 作为应用用户，我希望查询请求遵循官方协议，以便正常获取加密 OTA 响应。

#### Acceptance Criteria

1. WHEN 系统创建查询请求，系统 SHALL 为每次请求生成随机设备标识、AES 密钥和初始化向量。
2. WHEN 系统提交查询请求，系统 SHALL 使用地区对应的 RSA 公钥保护 AES 密钥，并使用 AES-CTR 加密请求体。
3. WHEN 系统接收响应，系统 SHALL 使用当前请求的 AES 密钥解密响应并解析 JSON 数据。

### Requirement 4: ROM Download

**User Story:** 作为 ROM 用户，我希望直接下载查询到的官方包，以便保存到设备。

#### Acceptance Criteria

1. WHEN 用户点击组件下载，系统 SHALL 使用动态下载链接启动现有后台下载服务。
2. WHILE ROM 下载进行中，系统 SHALL 显示文件名、百分比、预计剩余时间、速度、已下载大小、暂停、继续和取消操作。
3. IF 动态下载链接失效，系统 SHALL 显示下载失败原因并允许用户重新查询后重试。

### Requirement 5: Source and License Notice

**User Story:** 作为项目维护者，我希望保留上游项目来源和许可证信息，以便满足开源协议要求。

#### Acceptance Criteria

1. WHEN 项目发布包含 OPlus 查询功能的版本，项目 SHALL 在源码中保留 OPlus-Tracker 项目地址和许可证说明。
2. WHEN 用户查看 OPlus 查询页，系统 SHALL 提供上游项目来源入口或来源文本。
