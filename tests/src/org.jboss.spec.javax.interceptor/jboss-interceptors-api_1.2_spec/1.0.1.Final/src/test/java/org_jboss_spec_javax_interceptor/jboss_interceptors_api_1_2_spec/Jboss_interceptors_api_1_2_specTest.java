/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_jboss_spec_javax_interceptor.jboss_interceptors_api_1_2_spec;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.interceptor.AroundConstruct;
import javax.interceptor.AroundInvoke;
import javax.interceptor.AroundTimeout;
import javax.interceptor.ExcludeClassInterceptors;
import javax.interceptor.ExcludeDefaultInterceptors;
import javax.interceptor.Interceptor;
import javax.interceptor.InterceptorBinding;
import javax.interceptor.Interceptors;
import javax.interceptor.InvocationContext;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Jboss_interceptors_api_1_2_specTest {

    @Test
    void interceptorAnnotationsExposeExpectedMetadata() throws Exception {
        assertRuntimeAnnotation(Interceptor.class, ElementType.TYPE);
        assertRuntimeAnnotation(InterceptorBinding.class, ElementType.ANNOTATION_TYPE);
        assertRuntimeAnnotation(Interceptors.class, ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR);
        assertRuntimeAnnotation(AroundConstruct.class, ElementType.METHOD);
        assertRuntimeAnnotation(AroundInvoke.class, ElementType.METHOD);
        assertRuntimeAnnotation(AroundTimeout.class, ElementType.METHOD);
        assertRuntimeAnnotation(ExcludeClassInterceptors.class, ElementType.METHOD, ElementType.CONSTRUCTOR);
        assertRuntimeAnnotation(ExcludeDefaultInterceptors.class, ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR);

        assertThat(Interceptor.class.isAnnotationPresent(Documented.class)).isTrue();
        assertThat(InterceptorBinding.class.isAnnotationPresent(Documented.class)).isTrue();
        assertThat(Monitored.class.isAnnotationPresent(InterceptorBinding.class)).isTrue();
        assertThat(MonitoringInterceptor.class.isAnnotationPresent(Interceptor.class)).isTrue();
        assertThat(MonitoringInterceptor.class.isAnnotationPresent(Monitored.class)).isTrue();

        Interceptors classLevelInterceptors = InterceptedComponent.class.getAnnotation(Interceptors.class);
        ExcludeDefaultInterceptors excludeDefaults = InterceptedComponent.class.getAnnotation(ExcludeDefaultInterceptors.class);
        Constructor<InterceptedComponent> constructor = InterceptedComponent.class.getConstructor(String.class);
        Method businessMethod = InterceptedComponent.class.getMethod("businessOperation", String.class);
        Method aroundConstructMethod = MonitoringInterceptor.class.getMethod("aroundConstruct", InvocationContext.class);
        Method aroundInvokeMethod = MonitoringInterceptor.class.getMethod("aroundInvoke", InvocationContext.class);
        Method aroundTimeoutMethod = MonitoringInterceptor.class.getMethod("aroundTimeout", InvocationContext.class);

        assertThat(classLevelInterceptors.value()).containsExactly(MonitoringInterceptor.class);
        assertThat(excludeDefaults).isNotNull();
        assertThat(constructor.isAnnotationPresent(Interceptors.class)).isTrue();
        assertThat(constructor.isAnnotationPresent(ExcludeDefaultInterceptors.class)).isTrue();
        assertThat(constructor.isAnnotationPresent(ExcludeClassInterceptors.class)).isTrue();
        assertThat(businessMethod.isAnnotationPresent(Monitored.class)).isTrue();
        assertThat(businessMethod.isAnnotationPresent(Interceptors.class)).isTrue();
        assertThat(businessMethod.isAnnotationPresent(ExcludeDefaultInterceptors.class)).isTrue();
        assertThat(businessMethod.isAnnotationPresent(ExcludeClassInterceptors.class)).isTrue();
        assertThat(aroundConstructMethod.isAnnotationPresent(AroundConstruct.class)).isTrue();
        assertThat(aroundInvokeMethod.isAnnotationPresent(AroundInvoke.class)).isTrue();
        assertThat(aroundTimeoutMethod.isAnnotationPresent(AroundTimeout.class)).isTrue();
    }

    @Test
    void interceptorPriorityConstantsRemainOrdered() {
        assertThat(Interceptor.Priority.PLATFORM_BEFORE).isZero();
        assertThat(Interceptor.Priority.LIBRARY_BEFORE).isEqualTo(1000);
        assertThat(Interceptor.Priority.APPLICATION).isEqualTo(2000);
        assertThat(Interceptor.Priority.LIBRARY_AFTER).isEqualTo(3000);
        assertThat(Interceptor.Priority.PLATFORM_AFTER).isEqualTo(4000);
        assertThat(List.of(
                Interceptor.Priority.PLATFORM_BEFORE,
                Interceptor.Priority.LIBRARY_BEFORE,
                Interceptor.Priority.APPLICATION,
                Interceptor.Priority.LIBRARY_AFTER,
                Interceptor.Priority.PLATFORM_AFTER
        )).isSorted();
    }

    @Test
    void invocationContextSupportsConstructorMethodAndTimerFlows() throws Exception {
        MonitoringInterceptor interceptor = new MonitoringInterceptor();
        Constructor<InterceptedComponent> constructor = InterceptedComponent.class.getConstructor(String.class);
        Method businessMethod = InterceptedComponent.class.getMethod("businessOperation", String.class);
        Method timeoutMethod = InterceptedComponent.class.getMethod("timeoutOperation");

        Map<String, Object> constructionData = new LinkedHashMap<>();
        RecordingInvocationContext constructionContext = new RecordingInvocationContext(
                null,
                null,
                constructor,
                new Object[] { "alpha" },
                constructionData,
                null,
                parameters -> new InterceptedComponent((String) parameters[0])
        );

        InterceptedComponent component = (InterceptedComponent) interceptor.aroundConstruct(constructionContext);
        assertThat(component.businessOperation("probe")).isEqualTo("alpha:probe");
        assertThat(constructionContext.getTarget()).isSameAs(component);
        assertThat(constructionContext.getParameters()).containsExactly("alpha");
        assertThat(constructionData)
                .containsEntry("constructedWith", "alpha")
                .containsEntry("constructorName", "InterceptedComponent");

        Map<String, Object> invocationData = new LinkedHashMap<>();
        RecordingInvocationContext invocationContext = new RecordingInvocationContext(
                component,
                businessMethod,
                null,
                new Object[] { "request" },
                invocationData,
                null,
                parameters -> component.businessOperation((String) parameters[0])
        );

        Object invocationResult = interceptor.aroundInvoke(invocationContext);
        assertThat(invocationResult).isEqualTo("alpha:request-intercepted");
        assertThat(invocationContext.getParameters()).containsExactly("request-intercepted");
        assertThat(invocationData)
                .containsEntry("methodName", "businessOperation")
                .containsEntry("targetType", InterceptedComponent.class.getSimpleName());

        String timer = "nightly";
        Map<String, Object> timeoutData = new LinkedHashMap<>();
        RecordingInvocationContext timeoutContext = new RecordingInvocationContext(
                component,
                timeoutMethod,
                null,
                new Object[0],
                timeoutData,
                timer,
                parameters -> component.timeoutOperation() + "@" + timer
        );

        Object timeoutResult = interceptor.aroundTimeout(timeoutContext);
        assertThat(timeoutResult).isEqualTo("timer-alpha@nightly");
        assertThat(timeoutData).containsEntry("timer", "nightly");
        assertThat(interceptor.events()).containsExactly(
                "construct:InterceptedComponent",
                "invoke:InterceptedComponent#businessOperation",
                "timeout:nightly"
        );
    }

    @Test
    void lifecycleInvocationContextRejectsParameterAccessAndAllowsProceed() throws Exception {
        LifecycleMonitoringInterceptor interceptor = new LifecycleMonitoringInterceptor();
        LifecycleComponent component = new LifecycleComponent("beta");
        Map<String, Object> lifecycleData = new LinkedHashMap<>();
        LifecycleInvocationContext lifecycleContext = new LifecycleInvocationContext(
                component,
                lifecycleData,
                () -> {
                    component.markStarted();
                    lifecycleData.put("callback", "completed");
                    return null;
                }
        );

        assertThat(lifecycleContext.getTarget()).isSameAs(component);
        assertThat(lifecycleContext.getMethod()).isNull();
        assertThat(lifecycleContext.getConstructor()).isNull();
        assertThat(lifecycleContext.getTimer()).isNull();
        assertThatThrownBy(lifecycleContext::getParameters)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Lifecycle callbacks do not expose invocation parameters");
        assertThatThrownBy(() -> lifecycleContext.setParameters(new Object[] { "ignored" }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Lifecycle callbacks do not expose invocation parameters");

        Object result = interceptor.aroundLifecycle(lifecycleContext);

        assertThat(result).isNull();
        assertThat(component.isStarted()).isTrue();
        assertThat(lifecycleData)
                .containsEntry("componentName", "beta")
                .containsEntry("callback", "completed")
                .containsEntry("afterProceed", true);
        assertThat(interceptor.events()).containsExactly(
                "lifecycle:beta:before",
                "lifecycle:beta:after"
        );
    }

    private static void assertRuntimeAnnotation(Class<? extends Annotation> annotationType, ElementType... expectedTargets) {
        Retention retention = annotationType.getAnnotation(Retention.class);
        Target target = annotationType.getAnnotation(Target.class);

        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(expectedTargets);
    }

    @InterceptorBinding
    @Target({ ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR })
    @Retention(RetentionPolicy.RUNTIME)
    private @interface Monitored {
    }

    @Interceptor
    @Monitored
    private static final class MonitoringInterceptor {
        private final List<String> events = new ArrayList<>();

        @AroundConstruct
        public Object aroundConstruct(InvocationContext invocationContext) throws Exception {
            Constructor<?> constructor = invocationContext.getConstructor();
            Object[] parameters = invocationContext.getParameters();

            invocationContext.setParameters(parameters);
            invocationContext.getContextData().put("constructedWith", parameters[0]);
            invocationContext.getContextData().put("constructorName", constructor.getDeclaringClass().getSimpleName());

            Object result = invocationContext.proceed();
            events.add("construct:" + constructor.getDeclaringClass().getSimpleName());
            return result;
        }

        @AroundInvoke
        public Object aroundInvoke(InvocationContext invocationContext) throws Exception {
            Method method = invocationContext.getMethod();
            Object target = invocationContext.getTarget();
            Object[] parameters = invocationContext.getParameters();
            Object[] rewrittenParameters = new Object[] { parameters[0] + "-intercepted" };

            invocationContext.setParameters(rewrittenParameters);
            invocationContext.getContextData().put("methodName", method.getName());
            invocationContext.getContextData().put("targetType", target.getClass().getSimpleName());
            events.add("invoke:" + target.getClass().getSimpleName() + "#" + method.getName());

            return invocationContext.proceed();
        }

        @AroundTimeout
        public Object aroundTimeout(InvocationContext invocationContext) throws Exception {
            Object timer = invocationContext.getTimer();

            invocationContext.getContextData().put("timer", timer);
            events.add("timeout:" + timer);
            return invocationContext.proceed();
        }

        private List<String> events() {
            return events;
        }
    }

    @Monitored
    @Interceptors(MonitoringInterceptor.class)
    @ExcludeDefaultInterceptors
    private static final class InterceptedComponent {
        private final String name;

        @Interceptors(MonitoringInterceptor.class)
        @ExcludeDefaultInterceptors
        @ExcludeClassInterceptors
        public InterceptedComponent(String name) {
            this.name = name;
        }

        @Monitored
        @Interceptors(MonitoringInterceptor.class)
        @ExcludeDefaultInterceptors
        @ExcludeClassInterceptors
        public String businessOperation(String input) {
            return name + ":" + input;
        }

        public String timeoutOperation() {
            return "timer-" + name;
        }
    }

    @Interceptor
    @Monitored
    private static final class LifecycleMonitoringInterceptor {
        private final List<String> events = new ArrayList<>();

        public Object aroundLifecycle(InvocationContext invocationContext) throws Exception {
            LifecycleComponent component = (LifecycleComponent) invocationContext.getTarget();

            invocationContext.getContextData().put("componentName", component.getName());
            events.add("lifecycle:" + component.getName() + ":before");
            Object result = invocationContext.proceed();
            invocationContext.getContextData().put("afterProceed", Boolean.TRUE);
            events.add("lifecycle:" + component.getName() + ":after");
            return result;
        }

        private List<String> events() {
            return events;
        }
    }

    private static final class LifecycleComponent {
        private final String name;
        private boolean started;

        private LifecycleComponent(String name) {
            this.name = name;
        }

        private String getName() {
            return name;
        }

        private boolean isStarted() {
            return started;
        }

        private void markStarted() {
            started = true;
        }
    }

    private static final class RecordingInvocationContext implements InvocationContext {
        private Object target;
        private final Method method;
        private final Constructor<?> constructor;
        private Object[] parameters;
        private final Map<String, Object> contextData;
        private final Object timer;
        private final ProceedHandler proceedHandler;

        private RecordingInvocationContext(
                Object target,
                Method method,
                Constructor<?> constructor,
                Object[] parameters,
                Map<String, Object> contextData,
                Object timer,
                ProceedHandler proceedHandler
        ) {
            this.target = target;
            this.method = method;
            this.constructor = constructor;
            this.parameters = parameters.clone();
            this.contextData = contextData;
            this.timer = timer;
            this.proceedHandler = proceedHandler;
        }

        @Override
        public Object getTarget() {
            return target;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Constructor<?> getConstructor() {
            return constructor;
        }

        @Override
        public Object[] getParameters() {
            return parameters.clone();
        }

        @Override
        public void setParameters(Object[] parameters) {
            this.parameters = parameters.clone();
        }

        @Override
        public Map<String, Object> getContextData() {
            return contextData;
        }

        @Override
        public Object getTimer() {
            return timer;
        }

        @Override
        public Object proceed() throws Exception {
            Object result = proceedHandler.proceed(parameters.clone());
            if (constructor != null && target == null) {
                target = result;
            }
            return result;
        }
    }

    private static final class LifecycleInvocationContext implements InvocationContext {
        private final Object target;
        private final Map<String, Object> contextData;
        private final LifecycleProceedHandler proceedHandler;

        private LifecycleInvocationContext(
                Object target,
                Map<String, Object> contextData,
                LifecycleProceedHandler proceedHandler
        ) {
            this.target = target;
            this.contextData = contextData;
            this.proceedHandler = proceedHandler;
        }

        @Override
        public Object getTarget() {
            return target;
        }

        @Override
        public Method getMethod() {
            return null;
        }

        @Override
        public Constructor<?> getConstructor() {
            return null;
        }

        @Override
        public Object[] getParameters() {
            throw new IllegalStateException("Lifecycle callbacks do not expose invocation parameters");
        }

        @Override
        public void setParameters(Object[] parameters) {
            throw new IllegalStateException("Lifecycle callbacks do not expose invocation parameters");
        }

        @Override
        public Map<String, Object> getContextData() {
            return contextData;
        }

        @Override
        public Object getTimer() {
            return null;
        }

        @Override
        public Object proceed() throws Exception {
            return proceedHandler.proceed();
        }
    }

    @FunctionalInterface
    private interface ProceedHandler {
        Object proceed(Object[] parameters) throws Exception;
    }

    @FunctionalInterface
    private interface LifecycleProceedHandler {
        Object proceed() throws Exception;
    }
}
