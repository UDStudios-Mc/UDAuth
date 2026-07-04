package dev.underground.udauth;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Registers UDAuth through FastLogin's public AuthPlugin contract without
 * bundling FastLogin classes into this plugin. This keeps the integration
 * compatible with the server's installed FastLogin build. */
final class FastLoginHook {
    private static final String AUTH_PLUGIN = "com.github.games647.fastlogin.core.hooks.AuthPlugin";

    private FastLoginHook() {
    }

    static Object register(UDAuthPlugin udauth) {
        Plugin fastLogin = udauth.getServer().getPluginManager().getPlugin("FastLogin");
        if (fastLogin == null || !fastLogin.isEnabled()) return null;

        try {
            ClassLoader loader = fastLogin.getClass().getClassLoader();
            Class<?> authPluginType = Class.forName(AUTH_PLUGIN, true, loader);
            InvocationHandler handler = (proxy, method, args) -> invoke(udauth, proxy, method, args);
            Object hook = Proxy.newProxyInstance(loader, new Class<?>[]{authPluginType}, handler);

            Object core = fastLogin.getClass().getMethod("getCore").invoke(fastLogin);
            Method setter = core.getClass().getMethod("setAuthPluginHook", authPluginType);
            setter.invoke(core, hook);
            return hook;
        } catch (ReflectiveOperationException | LinkageError exception) {
            udauth.getLogger().severe("Could not register the FastLogin authentication hook: "
                    + exception.getMessage());
            return null;
        }
    }

    private static Object invoke(UDAuthPlugin udauth, Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "forceLogin" -> udauth.fastLoginForceLogin((Player) args[0]);
            case "forceRegister" -> udauth.fastLoginForceRegister((Player) args[0], (String) args[1]);
            case "isRegistered" -> udauth.fastLoginIsRegistered((String) args[0]);
            case "toString" -> "UDAuthFastLoginHook";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Unsupported FastLogin hook method: " + method);
        };
    }
}
