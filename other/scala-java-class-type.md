Java/Scala类型系统
======
by chenze

Scala对于每一个程序员甚至Java程序员来说,它的类型系统都过于复杂.协变,逆变,上界定,下界定,视图界定,上下文界定,Manifest,ClassType一堆一堆新奇的概念,让人都在怀疑自己是在学习一门编程语言还是在研究一本数学课本. 你也许会说,我为什么要知道这些?我可以熟练的使用flatmap,map,reduce不就可以吗?当然,从应用的角度来说,没有问题!但是前几天看到一个帖子,有人说它精通Spark-Core,但是有人问了一句:Scala的ClassType在Spark-Core的作用?它就蒙了!当然我也蒙了,研究一翻发现,仅仅Spark-Core就有800处有关ClassType的应用,连RDD的定义都是`abstract class RDD[T: ClassTag]`,想想一下,如果对scala类型系统不了解,怎么去写Spark代码?

### Java中类型系统:"类"!="类型"

在Java的世界中,类为Class,而类型为Type.在jdk1.5版本之前,没有泛型的存在,对于一个对象(Object),通过getClass就可以获取到这个对象的Class,此时我们就明确的知道这个对象的Type,即Class与Type有一对一关系.比如对于`Integer a; a.getClass == Integer.class`此时我们就明确知道`typeof(a) == class Integer`;

但是引入泛型以后,一个变量的Type和Class不再一一对应了,比如`List<String>和List<Integer>的Class都是Class<List>，但是两者的Type不一样了`,产生这种原因是在JVM层面是不支持泛型的,对于Java,泛型是工作在javac编译层,在编译过程中,会对泛型相关信息从中擦除,即`List<String>和List<Integer>都被表示为List`,但是类型信息还是必须存储的,对于Java由`ParameterizedType, TypeVariable<D>, GenericArrayType, WildcardType`来存储泛型变量的类型信息.对于非泛型,如前面所说,他们类型就是Class.为了程序的扩展性,最终引入了`Type接口作为Class和ParameterizedType, TypeVariable<D>, GenericArrayType, WildcardType这几种类型的总的父接口`,这样可以用Type类型的参数来接受以上五种子类的实参或者返回值类型就是Type类型的参数,统一了与泛型有关的类型和原始类型Class.

####关于四种泛型的类型的解释(摘录自:loveshisong.cn)

ParameterizedType:具体的范型类型, 如Map<String, String>.

    有如下方法:
    1. Type getRawType(): 返回承载该泛型信息的对象, 如上面那个Map<String, String>承载范型信息的对象是Map
    2. Type[] getActualTypeArguments(): 返回实际泛型类型列表, 如上面那个Map<String, String>实际范型列表中有两个元素, 都是String
    3. Type getOwnerType(): 返回是谁的member.(上面那两个最常用)
    public class TestType {
        Map<String, String> map;
        public static void main(String[] args) throws Exception {
            Field f = TestType.class.getDeclaredField("map");
            System.out.println(f.getGenericType());
            // java.util.Map<java.lang.String, java.lang.String>
            System.out.println(f.getGenericType() instanceof ParameterizedType);
            // true
            ParameterizedType pType = (ParameterizedType) f.getGenericType();
            System.out.println(pType.getRawType());
            // interface java.util.Map
            for (Type type : pType.getActualTypeArguments()) {
                System.out.println(type);
                // 打印:class java.lang.String
            }
            System.out.println(pType.getOwnerType());
            // null
        }
    }

TypeVariable:类型变量, 范型信息在编译时会被转换为一个特定的类型, 而TypeVariable就是用来反映在JVM编译该泛型前的信息.它的声明是这样的: `public interface TypeVariable<D extends GenericDeclaration> extends Type`,也就是说它跟GenericDeclaration有一定的联系, 我是这么理解的:`TypeVariable是指在GenericDeclaration中声明的<T>、<C extends Collection>这些东西中的那个变量T、C;` .

    它有如下方法:
    1. Type[] getBounds(): 获取类型变量的上边界, 若未明确声明上边界则默认为Object
    2. D getGenericDeclaration(): 获取声明该类型变量实体
    3. String getName(): 获取在源码中定义时的名字

    注意:
    1. 类型变量在定义的时候只能使用extends进行(多)边界限定, 不能用super;
    2. 为什么边界是一个数组? 因为类型变量可以通过&进行多个上边界限定，因此上边界有多个
    public class TestType <K extends Comparable & Serializable, V> {
        K key;
        V value;
        public static void main(String[] args) throws Exception {
            // 获取字段的类型
            Field fk = TestType.class.getDeclaredField("key");
            Field fv = TestType.class.getDeclaredField("value");
            Assert.that(fk.getGenericType() instanceof TypeVariable, "必须为TypeVariable类型");
            Assert.that(fv.getGenericType() instanceof TypeVariable, "必须为TypeVariable类型");
            TypeVariable keyType = (TypeVariable)fk.getGenericType();
            TypeVariable valueType = (TypeVariable)fv.getGenericType();
            // getName 方法
            System.out.println(keyType.getName());
            // K
            System.out.println(valueType.getName());
            // V
            // getGenericDeclaration 方法
            System.out.println(keyType.getGenericDeclaration());
            // class com.test.TestType
            System.out.println(valueType.getGenericDeclaration());
            // class com.test.TestType
            // getBounds 方法
            System.out.println("K 的上界:");
            // 有两个
            // interface java.lang.Comparable
            // interface java.io.Serializable
            for (Type type : keyType.getBounds()) {
                System.out.println(type);
            }
            System.out.println("V 的上界:");
            // 没明确声明上界的, 默认上界是 Object
            // class java.lang.Object
            for (Type type : valueType.getBounds()) {
                System.out.println(type);
            }
        }
    }

GenericArrayType: 范型数组,组成数组的元素中有范型则实现了该接口; 它的组成元素是ParameterizedType或TypeVariable类型.

    它只有一个方法:
    1. Type getGenericComponentType(): 返回数组的组成对象, 即被JVM编译后实际的对象
    public class TestType <T> {
        public static void main(String[] args) throws Exception {
            Method method = Test.class.getDeclaredMethods()[0];
            // public void com.test.Test.show(java.util.List[],java.lang.Object[],java.util.List,java.lang.String[],int[])
            System.out.println(method);
            Type[] types = method.getGenericParameterTypes();  // 这是 Method 中的方法
            for (Type type : types) {
                System.out.println(type instanceof GenericArrayType);
            }
        }
    }
    class Test<T> {
        public void show(List<String>[] pTypeArray, T[] vTypeArray, List<String> list, String[] strings, int[] ints) {
        }
    }
    第一个参数List<String>[]的组成元素List<String>是ParameterizedType类型, 打印结果为true
    第二个参数T[]的组成元素T是TypeVariable类型, 打印结果为true
    第三个参数List<String>不是数组, 打印结果为false
    第四个参数String[]的组成元素String是普通对象, 没有范型, 打印结果为false
    第五个参数int[] pTypeArray的组成元素int是原生类型, 也没有范型, 打印结果为false

WildcardType: 该接口表示通配符泛型, 比如? extends Number 和 ? super Integer.

    它有如下方法:
    1. Type[] getUpperBounds(): 获取范型变量的上界
    2. Type[] getLowerBounds(): 获取范型变量的下界
    注意:现阶段通配符只接受一个上边界或下边界, 返回数组是为了以后的扩展, 实际上现在返回的数组的大小是1
    public class TestType {
        private List<? extends Number> a;  // // a没有下界, 取下界会抛出ArrayIndexOutOfBoundsException
        private List<? super String> b;
        public static void main(String[] args) throws Exception {
            Field fieldA = TestType.class.getDeclaredField("a");
            Field fieldB = TestType.class.getDeclaredField("b");
            // 先拿到范型类型
            Assert.that(fieldA.getGenericType() instanceof ParameterizedType, "");
            Assert.that(fieldB.getGenericType() instanceof ParameterizedType, "");
            ParameterizedType pTypeA = (ParameterizedType) fieldA.getGenericType();
            ParameterizedType pTypeB = (ParameterizedType) fieldB.getGenericType();
            // 再从范型里拿到通配符类型
            Assert.that(pTypeA.getActualTypeArguments()[0] instanceof WildcardType, "");
            Assert.that(pTypeB.getActualTypeArguments()[0] instanceof WildcardType, "");
            WildcardType wTypeA = (WildcardType) pTypeA.getActualTypeArguments()[0];
            WildcardType wTypeB = (WildcardType) pTypeB.getActualTypeArguments()[0];
            // 方法测试
            System.out.println(wTypeA.getUpperBounds()[0]);   // class java.lang.Number
            System.out.println(wTypeB.getLowerBounds()[0]);   // class java.lang.String
            // 看看通配符类型到底是什么, 打印结果为: ? extends java.lang.Number
            System.out.println(wTypeA);
        }
    }
    再写几个边界的例子:
    List<? extends Number>, 上界为class java.lang.Number, 属于Class类型
    List<? extends List<T>>, 上界为java.util.List<T>, 属于ParameterizedType类型
    List<? extends List<String>>, 上界为java.util.List<java.lang.String>, 属于ParameterizedType类型
    List<? extends T>, 上界为T, 属于TypeVariable类型
    List<? extends T[]>, 上界为T[], 属于GenericArrayType类型
    它们最终统一成Type作为数组的元素类型

