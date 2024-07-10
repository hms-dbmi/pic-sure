package edu.harvard.hms.dbmi.avillach.auth.config;

import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

public class CustomKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        for (Object param : params) {
            if (param instanceof User user) {
                return user.getEmail();
            }
        }

        throw new IllegalArgumentException("No valid params found. Cannot generate cache key");
    }
}
