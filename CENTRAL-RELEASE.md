# mybatis-plus-with-wherehas 发布到 Maven Central 说明

本文档用于指导 `io.github.skyleew:mybatis-plus-with-wherehas` 发布到 Sonatype Central Portal。

## 1. 发布前提

- 已创建公开仓库：`https://github.com/SkyLeeW/mybatis-plus-with-wherehas`
- 已登录 [Central Portal](https://central.sonatype.com/)
- 已确认 namespace 为 `io.github.skyleew`
- 已生成 Central Portal token
- 本机已安装 Maven 与 GPG

## 2. GPG 准备

先确认本机是否已有可用私钥：

```powershell
gpg --list-secret-keys --keyid-format LONG
```

如果还没有私钥，可执行：

```powershell
gpg --full-generate-key
```

生成完成后记下长 KeyId，例如：

```text
sec   rsa4096/1234ABCD5678EF90 2026-04-07 [SC]
```

这里的 `1234ABCD5678EF90` 就是后续配置里的 `gpg.keyname`。

## 3. Maven settings.xml 配置

Windows 默认路径：

```text
C:\Users\你的用户名\.m2\settings.xml
```

推荐使用下面的最小配置：

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>你的CentralToken用户名</username>
      <password>你的CentralToken密码</password>
    </server>
  </servers>

  <profiles>
    <profile>
      <id>central-release</id>
      <properties>
        <gpg.keyname>你的GPG长KeyId</gpg.keyname>
        <gpg.passphrase>你的GPG口令</gpg.passphrase>
      </properties>
    </profile>
  </profiles>

  <activeProfiles>
    <activeProfile>central-release</activeProfile>
  </activeProfiles>
</settings>
```

如果不希望明文保存口令，可以把 `gpg.passphrase` 改成命令行传参或 Maven 密文配置。

## 4. 本模块当前发布坐标

```xml
<dependency>
    <groupId>io.github.skyleew</groupId>
    <artifactId>mybatis-plus-with-wherehas</artifactId>
    <version>1.0.3</version>
</dependency>
```

## 5. 发布命令

在有 Maven 的环境里执行：

```powershell
mvn -f D:\dev\base\jieyun-base-java-dev-package\jieyun-common\mybatis-plus-with-wherehas\pom.xml clean deploy
```

命令会完成以下动作：

- 编译源码
- 生成 `sources.jar`
- 生成 `javadoc.jar`
- 对构件执行 GPG 签名
- 通过 `central-publishing-maven-plugin` 上传到 Central Portal

## 6. 发布后的 Portal 操作

当前 `pom.xml` 配置了：

- `autoPublish=true`
- `waitUntil=validated`

这意味着上传成功后，Portal 会先完成校验，校验通过后自动对外发布。

你仍然可以到下面页面查看部署状态：

- [Deployments](https://central.sonatype.com/publishing/deployments)

## 7. 首次发布建议检查项

- `pom.xml` 中的 `url`、`scm`、`licenses`、`developers` 都是有效真实值
- 仓库里存在 `LICENSE`
- 版本号没有和已发布版本重复
- GitHub 仓库已公开
- 本地 `gpg --list-secret-keys` 能看到目标私钥
- `settings.xml` 中的 `central` 认证信息可用

## 8. 常见问题

### 8.1 `mvn` 命令不存在

说明本机没有安装 Maven，或者 Maven 未加入 `PATH`。  
先执行：

```powershell
mvn -version
```

如果失败，需要先安装 Maven。

### 8.2 签名失败

通常是以下原因之一：

- `gpg.keyname` 写错
- GPG 私钥不存在
- GPG 口令错误
- GPG 未加入 `PATH`

### 8.3 Portal 校验失败

优先检查：

- 命名空间是否和 `groupId` 一致
- `sources.jar`、`javadoc.jar` 是否生成
- 签名文件是否生成
- POM 元数据是否完整

## 9. 本仓库当前状态说明

当前仓库已经具备 Maven Central 的发布配置。  
正式发布前，仍需要在已安装 Maven 与 GPG，且已配置 Central 凭据与签名信息的机器上执行一次完整发布验证。
