# 《滚木派对》Android 混淆规则

# libGDX 通过反射实例化入口类，必须保留
-keep class top.gunmu.party.android.AndroidLauncher { *; }
-keep class top.gunmu.party.GunmuPartyGame { *; }

# 游戏内的枚举全部由名称驱动（地图 id、道具 id 等），别被裁掉
-keepclassmembers enum top.gunmu.party.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class top.gunmu.party.map.MapRegistry { *; }
-keep class top.gunmu.party.items.ItemType { *; }
-keep class top.gunmu.party.ultimates.UltimateType { *; }

# libGDX 原生接口
-keep class com.badlogic.gdx.** { *; }
-dontwarn com.badlogic.gdx.**

# FreeType
-keep class com.badlogic.gdx.graphics.g2d.freetype.** { *; }
