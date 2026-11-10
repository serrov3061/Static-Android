package com.exteragram.messenger.plugins.hooks;

import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.utils.AppUtils;
import de.robv.android.xposed.XC_MethodHook;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import kotlin.jvm.internal.Intrinsics;
import org.mvel2.MVEL;

public final class HookFilter {

    public static final Companion Companion = new Companion();
    private static final int TYPE_ARGUMENT_EQUAL = 3;
    private static final int TYPE_ARGUMENT_IS_FALSE = 6;
    private static final int TYPE_ARGUMENT_IS_INSTANCE_OF = 5;
    private static final int TYPE_ARGUMENT_IS_NULL = 7;
    private static final int TYPE_ARGUMENT_IS_TRUE = 8;
    private static final int TYPE_ARGUMENT_NOT_EQUAL = 4;
    private static final int TYPE_ARGUMENT_NOT_NULL = 9;
    private static final int TYPE_CONDITION = 2;
    private static final int TYPE_OR = 1;
    private static final int TYPE_RESULT_EQUAL = 10;
    private static final int TYPE_RESULT_IS_FALSE = 12;
    private static final int TYPE_RESULT_IS_INSTANCE_OF = 13;
    private static final int TYPE_RESULT_IS_NULL = 14;
    private static final int TYPE_RESULT_IS_TRUE = 15;
    private static final int TYPE_RESULT_NOT_EQUAL = 11;
    private static final int TYPE_RESULT_NOT_NULL = 16;
    private static final int TYPE_UNKNOWN = 0;
    private Integer argIndex;
    private volatile CompiledCondition compiledCondition;
    private final String filterType;
    private Class<?> instanceOf;
    private String mvelExpression;
    private Object object;
    private ArrayList<HookFilter> orFilters;
    private final int typeId;

    public HookFilter(String str) {
        this.filterType = str;
        this.typeId = Companion.typeIdFor(str);
    }

    public final String getFilterType() {
        return this.filterType;
    }

    public static final class CompiledCondition {
        private final Serializable expression;
        private final String source;

        public CompiledCondition(String str, Serializable serializable) {
            this.source = str;
            this.expression = serializable;
        }

        public final Serializable getExpression() {
            return this.expression;
        }

        public final String getSource() {
            return this.source;
        }
    }

    public final Integer getArgIndex() {
        return this.argIndex;
    }

    public final void setArgIndex(Integer num) {
        this.argIndex = num;
    }

    public final ArrayList<HookFilter> getOrFilters() {
        return this.orFilters;
    }

    public final void setOrFilters(ArrayList<HookFilter> arrayList) {
        this.orFilters = arrayList;
    }

    public final String getMvelExpression() {
        return this.mvelExpression;
    }

    public final void setMvelExpression(String str) {
        this.mvelExpression = str;
    }

    public final Class<?> getInstanceOf() {
        return this.instanceOf;
    }

    public final void setInstanceOf(Class<?> cls) {
        this.instanceOf = cls;
    }

    public final Object getObject() {
        return this.object;
    }

    public final void setObject(Object obj) {
        this.object = obj;
    }

    public final boolean execute(XC_MethodHook.MethodHookParam param, boolean isBefore) {
        Serializable compiledExpression;
        Boolean bool;
        try {
            switch (this.typeId) {
                case 1:
                    ArrayList<HookFilter> arrayList = this.orFilters;
                    if (arrayList != null) {
                        Iterator<HookFilter> it = arrayList.iterator();
                        while (it.hasNext()) {
                            if (it.next().execute(param, isBefore)) {
                                return true;
                            }
                        }
                    }
                    return false;
                case 2:
                    HashMap<String, Object> map = new HashMap<>(4);
                    map.put("param", param);
                    map.put("result", isBefore ? null : param.getResult());
                    map.put("object", this.object);
                    String str = this.mvelExpression;
                    if (str == null || (compiledExpression = getCompiledExpression(str)) == null || (bool = MVEL.executeExpression(compiledExpression, param.thisObject, map, Boolean.class)) == null) {
                        return false;
                    }
                    return bool.booleanValue();
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                case 8:
                case 9:
                    Integer num = this.argIndex;
                    Object[] objArr = param.args;
                    if (num != null && num >= 0 && num < objArr.length) {
                        Object obj = objArr[num.intValue()];
                        switch (this.typeId) {
                            case 3:
                                return valuesEqual(obj, this.object);
                            case 4:
                                return !valuesEqual(obj, this.object);
                            case 5:
                                Class<?> cls = this.instanceOf;
                                return cls != null && cls.isInstance(obj);
                            case 6:
                                return (obj instanceof Boolean) && !((Boolean) obj).booleanValue();
                            case 7:
                                return obj == null;
                            case 8:
                                return (obj instanceof Boolean) && ((Boolean) obj).booleanValue();
                            case 9:
                                return obj != null;
                            default:
                                return false;
                        }
                    }
                    return false;
                case 10:
                case 11:
                case 12:
                case 13:
                case 14:
                case 15:
                case 16:
                    if (isBefore) {
                        return false;
                    }
                    Object result = param.getResult();
                    switch (this.typeId) {
                        case 10:
                            return valuesEqual(result, this.object);
                        case 11:
                            return !valuesEqual(result, this.object);
                        case 12:
                            return (result instanceof Boolean) && !((Boolean) result).booleanValue();
                        case 13:
                            Class<?> cls2 = this.instanceOf;
                            return cls2 != null && cls2.isInstance(result);
                        case 14:
                            return result == null;
                        case 15:
                            return (result instanceof Boolean) && ((Boolean) result).booleanValue();
                        case 16:
                            return result != null;
                        default:
                            return false;
                    }
                default:
                    return false;
            }
        } catch (Exception e) {
            AppUtils.log(e);
            return false;
        }
    }

    private final Serializable getCompiledExpression(String expression) {
        Serializable serializableCompileExpression;
        CompiledCondition compiledCondition = this.compiledCondition;
        if (compiledCondition != null && Intrinsics.areEqual(compiledCondition.getSource(), expression)) {
            return compiledCondition.getExpression();
        }
        try {
            serializableCompileExpression = MVEL.compileExpression(expression);
        } catch (Exception e) {
            AppUtils.log(e);
            serializableCompileExpression = null;
        }
        this.compiledCondition = new CompiledCondition(expression, serializableCompileExpression);
        return serializableCompileExpression;
    }

    private final boolean valuesEqual(Object a2, Object b2) {
        if (Intrinsics.areEqual(a2, b2)) {
            return true;
        }
        if ((a2 instanceof Number) && (b2 instanceof Number)) {
            if (!(a2 instanceof Double) && !(a2 instanceof Float) && !(b2 instanceof Double) && !(b2 instanceof Float)) {
                return ((Number) a2).longValue() == ((Number) b2).longValue();
            }
            if (((Number) a2).doubleValue() == ((Number) b2).doubleValue()) {
                return true;
            }
        }
        return false;
    }

    public static final class Companion {
        private Companion() {
        }

        public final int typeIdFor(String filterType) {
            switch (filterType.hashCode()) {
                case -1842277382:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.ARGUMENT_IS_NULL) ? 0 : 7;
                case -1842101247:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.ARGUMENT_IS_TRUE) ? 0 : 8;
                case -1369450155:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.RESULT_NOT_NULL) ? 0 : 16;
                case -1284007664:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.ARGUMENT_IS_FALSE) ? 0 : 6;
                case -1248106702:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.ARGUMENT_EQUAL) ? 0 : 3;
                case -861311717:
                    return !filterType.equals("condition") ? 0 : 2;
                case -170795378:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.RESULT_IS_INSTANCE_OF) ? 0 : 13;
                case 3555:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.OR) ? 0 : 1;
                case 180205237:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.ARGUMENT_NOT_NULL) ? 0 : 9;
                case 488295718:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.RESULT_NOT_EQUAL) ? 0 : 11;
                case 516769938:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.RESULT_EQUAL) ? 0 : 10;
                case 1282972614:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.ARGUMENT_NOT_EQUAL) ? 0 : 4;
                case 1461304240:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.RESULT_IS_FALSE) ? 0 : 12;
                case 1877750766:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.ARGUMENT_IS_INSTANCE_OF) ? 0 : 5;
                case 1987059034:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.RESULT_IS_NULL) ? 0 : 14;
                case 1987235169:
                    return !filterType.equals(PluginsConstants.HookFilterTypes.RESULT_IS_TRUE) ? 0 : 15;
                default:
                    return 0;
            }
        }
    }
}
