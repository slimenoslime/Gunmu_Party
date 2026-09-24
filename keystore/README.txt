滚木派对 · 固定签名（applicaton signing key）
============================================

⚠️ 这个文件就是本作的**唯一签名**。从 a0.0.1 起，所有构建都用它签名。
   **丢了它 = 已安装的用户永远无法升级**（安卓不允许同包名换签名覆盖安装，
   只能卸载重装、存档全丢），也没法再往外发布更新。所以：

   - 别删、别改名、别重新生成；
   - 备份到至少两个地方（网盘 / 另一台机器 / 密码管理器）；
   - 换机器时把它一起拷过去（连同下面的密码）。

新增文件一览
------------

  keystore/gunmu-party.jks     签名文件本体（PKCS12，2.6 KB）
  keystore/README.txt          本说明（密码与指纹就写在这里）

签名参数（脚本与 Gradle 都用这一套）
------------------------------------

  文件          keystore/gunmu-party.jks
  存储类型      PKCS12
  别名          gunmu-party
  存储密码      gunmu-party
  密钥密码      gunmu-party      （PKCS12 下与存储密码相同）
  密钥算法      RSA 2048
  证书算法      SHA384withRSA
  主体          CN=Gunmu Party, OU=Gunmu, O=Gunmu, C=CN
  有效期        2026-09-21 → 2054-02-06（10000 天）

证书指纹（用于核对"这一版是不是同一把钥匙签的"）
------------------------------------------------

  SHA-256   93:78:EA:E8:89:74:39:64:24:FB:F2:26:F8:6C:FF:63:
            F2:1E:01:4B:10:50:38:D9:CB:C3:50:54:C4:F1:C7:F5
  SHA-1     DF:9E:C1:63:8D:08:3D:45:49:9B:21:3E:AF:E2:D1:3B:81:90:4D:C6

核对方法：

  C:/Program Files/BellSoft/LibericaJDK-21/bin/keytool.exe -list -v ^
      -keystore keystore/gunmu-party.jks -storepass gunmu-party -alias gunmu-party

  或对已打出的 APK：

  <build-tools>/apksigner.bat verify --print-certs build/apk/gunmu-party-a0.0.1.apk

怎么用
------

  python tools/build_apk.py          # 默认就是用这把签名（脚本里写死了上面的参数）
  python tools/build_apk.py --keystore <其它.jks> --ks-pass <密码> --ks-alias <别名>
                                     # 需要时也可临时换一把（不推荐）

Gradle 那条路（本机受限环境跑不动，仅供参考）也已经在 android/build.gradle 里
配好了 signingConfigs.fixed，debug / release 都用它。

重建一把同样的签名（只有确认原件彻底丢了才做）
----------------------------------------------

  keytool -genkeypair -v -keystore keystore/gunmu-party.jks -storetype PKCS12 ^
      -alias gunmu-party -keyalg RSA -keysize 2048 -validity 10000 ^
      -storepass gunmu-party -keypass gunmu-party ^
      -dname "CN=Gunmu Party, OU=Gunmu, O=Gunmu, C=CN"

⚠️ 这样重建出来的是**另一把钥匙**（指纹不同），对已装用户而言和丢了一样。
   旧版本（a0.0.1 之前用 Android debug key 签的那些）同样无法覆盖升级，
   设备上要先卸载旧包。


为什么不再用 Android 的 debug key
---------------------------------

  之前 build_apk.py 用的是 build/debug.keystore（从 ~/.android/debug.keystore 拷来的）。
  两个问题：
    1. `build/` 在 .gitignore 里（它是构建产物目录），**不入库** → 换机器/清理后就没了；
    2. ~/.android/debug.keystore 是本机 Android 工具链生成的，每台机器都不一样，
       别人拿到源码打出来的包和你打出来的包**签名不同**，互相装不上（报
       INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
  固定签名必须放在**入库目录**（keystore/），并且密码写下来 —— 这就是这个目录的存在意义。
