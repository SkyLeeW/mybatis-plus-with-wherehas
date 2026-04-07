package io.github.skyleew.relationmapping.support;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 保存 Spring 容器上下文，供无法直接注入的静态工具读取基础 Bean。
 */
@Component
public class SpringContextHolder implements ApplicationContextAware {

    /**
     * 静态持有应用上下文，确保静态工具在运行期可统一取用容器 Bean。
     */
    private static ApplicationContext applicationContext;

    /**
     * 在容器启动时回填 ApplicationContext，供静态访问入口复用。
     *
     * @param applicationContext Spring 应用上下文
     * @throws BeansException 容器回调异常
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        SpringContextHolder.applicationContext = applicationContext;
    }

    /**
     * 按类型从 Spring 容器中获取 Bean，不存在上下文时直接抛出非法状态异常。
     *
     * @param beanClass Bean 类型
     * @param <T> Bean 泛型
     * @return 容器中的 Bean 实例
     */
    public static <T> T getBean(Class<T> beanClass) {
        if (applicationContext == null) {
            throw new IllegalStateException("Spring ApplicationContext 尚未初始化");
        }
        return applicationContext.getBean(beanClass);
    }
}
