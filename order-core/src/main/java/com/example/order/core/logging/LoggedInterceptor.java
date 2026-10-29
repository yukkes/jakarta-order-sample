package com.example.order.core.logging;

import com.example.order.core.web.error.BusinessException;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.util.logging.Level;
import java.util.logging.Logger;

@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LoggedInterceptor {

    private static final Logger LOG = Logger.getLogger(LoggedInterceptor.class.getName());

    @AroundInvoke
    Object log(InvocationContext context) throws Exception {
        long started = System.nanoTime();
        String method = context.getMethod().getDeclaringClass().getSimpleName()
                + "." + context.getMethod().getName();
        try {
            Object result = context.proceed();
            LOG.fine(() -> "[interceptor] " + method + " took "
                    + ((System.nanoTime() - started) / 1_000_000) + " ms");
            return result;
        } catch (BusinessException exception) {
            LOG.fine(() -> "[interceptor] " + method + " rejected: " + exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "[interceptor] " + method + " failed", exception);
            throw exception;
        }
    }
}
