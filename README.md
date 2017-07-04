RxJava 1.x 源码分析
========
1. Demo 分析
最简单的 demo
```java
Observable observable = Observable.create(new Observable.OnSubscribe<String>() {
    @Override
    public void call(Subscriber<? super String> subscriber) {
        subscriber.onNext("Hello");
        subscriber.onNext("Hi");
        subscriber.onCompleted();
    }
});
Subscriber<String> subscriber = new Subscriber<String>() {
    @Override
    public void onNext(String s) {
        Log.d(tag, "Item: " + s);
    }
    @Override
    public void onCompleted() {
        Log.d(tag, "Completed!");
    }
    @Override
    public void onError(Throwable e) {
        Log.d(tag, "Error!");
    }
};
observable.subscribe(subscriber);
```
使用步骤:
 1. 首先创建 Observable 观察源,通过 Observable.create 静态方法,传入 Observable.OnSubscribe 对象,
 需要实现`void call(Subscriber subscriber)`方法.
 2. 再创建 Subscriber 观察者,实现它的方法`onNext`,`onCompleted`,`onError`.
 3. 最后通过调用 Observable 的 subscribe 方法,即订阅,观察者和观察源产生联系.
 
![png](http://plantuml.com/plantuml/png/TP2n5i8W34NtVWKZ7Lft1nUFgmxw0q0pU0JYIQdrywLQWQQwljoybnSr1FI3qq2LCW59EAWSY6OdJluywDxGLOFO2WzZgSYsRUkgH9guuYOIi_XrqZtGGsaxZGs12Y0BAlfaWEFlt2JyNqlfIPl34q-pMnO-9GeWkVH9-rKXJMNcU8b5RBmwA0forNJnOgEibUofdEbGZ4Wt3h6q8pp3seb1UtHt2m4wUQatw0z4I39-zGK0)

2. 基本 demo 的函数调用链
去掉部分与性能、兼容性、扩展性有关的代码和函数调用,仅关注核心代码和调用,每一个函数和关键点在对象源码文件里有对应注释
    - `Observable.create` 创建观察源
        - `new Observable.OnSubscribe()`创建OnSubscribe对象
        - `new Observable<T>(OnSubscribe<T> f)`把创建好的OnSubscribe对象丢到构造方法里, new了一个`Observable`
    - `new Subscriber<T>()` 创建观察者对象
    - `observable.subscribe(subscriber)` 将观察源和观察者建立联系,然后搞事情
        - `subscriber.onStart()`回调观察者的`onStart()`方法,即在搞事情之前回调,可以做些准备
        - `observable.onSubscribe.call(subscriber)` 调用onSubscribe.call() 方法,就是 Observable.create()传入的
            - `Subscriber.onNext(T t)` 这里的 Subscriber 就是我们自定义的subscriber
            - `Subscriber.onCompleted()` 和 onNext 类似
ok,基本 demo 分析完了,看整个调用流程其实并不复杂,跟踪下来还是很容易的,上述的每个调用部分我都做了一些注释.
3. 深入使用的源码分析
RxJava 最强大的就是操作符 和 线程操作,接下来看看这部分.
 1. 操作符
      -  map
          - `Observable.map()`
            - `create(new OnSubscribeMap<T, R>())` map 的具体逻辑,新生产了一个OnSubscribe类代替了原先的
                - `OnSubscribeMap.call()`会调用新的 call()
                    - `new MapSubscriber<T, R>(o, transformer)` MapSubscriber会订阅原来的Observable,也就是会调原先OnSubscribe的 call()
                    - `MapSubscriber.onNext()`首先会调 map转换函数,再调真实的Subscriber.onNext(),也就是我们自定义传入的匿名类
          举个例子说明
          ```java
              Func1 mapFun=new Func1<Integer, String>() {
                               @Override
                               public String call(Integer number) { // 参数类型 int
                                   return "number " + number; // 返回类型 String
                               }
                           };
              Action1 action1=new Action1<String>() {
                            @Override
                            public void call(String str) { // 参数类型 String
                                Log.i(str);
                            }
                        };
              Observable.just(1,2,3,4,5)
              .map(mapFun)
              .subscribe(action1);
          ```
          这个例子是把 数字 转变为 "number "+数字
          将Observable.just(1,2,3,4,5)的将Observable 记做 sourceObservable , map(mapFun)方法调用后,
          OnSubscribeMap 保存了 sourceObservable 和 mapFun,并 new 一个新的Observable,记做 MapObservable,
          它的 onSubscribe 就是 OnSubscribeMap,
          当调用subscribe(action1)时,action1 会包装成Subscriber ,记做 targetSubscriber,
          首先会调 OnSubscribeMap.call(targetSubscriber),然后 new MapSubscriber(sourceObservable,mapFun) 记做 parent,
          再调用 targetSubscriber.add(parent) ,这是为了保证调用 targetSubscriber 的unsubscribe()和isUnsubscribed() 能调用到 parent 的对应方法中,
          最后调用 sourceObservable.unsafeSubscribe(parent),这个是 parent 订阅 sourceObservable,当 sourceObservable 调用 onNext()后,
          parent 的onNext()会被调用,它的 onNext()方法首先会调用mapFun()方法将数据转换,将转换后的结果传给targetSubscriber.onNext().
          
          说的有点复杂,其实可以把 MapSubscriber 看做是 targetSubscriber 的包装,它首先会执行 mapFun 将数据转换,再回调 targetSubscriber
        
      - lift 变换
        变换 Subscriber ,执行变换的代码,变换的过程是`Operator<? extends R, ? super T>`,
        通过 `OnSubscribeLift(OnSubscribe<T> parent, Operator operator)`的 call() 方法,
        首先将 subscriber `operator.call(o)` 转换一下,再执行`parent.call(st);`调用原来的OnSubscribe的逻辑,达到了变换的效果.
        和上面的 map 操作符对比,其实流程大致类似,区别在于, map 是新建 Subscriber 订阅上层,从上层获取数据,
        然后调用 map 转换数据,最后再通过 onNext 等方法返回给真正的 Subscriber,可以看做是一个从上至下的转换;
        而 lift 的原理是:先通过 Operator 将下层真正的 Subscriber 转换成上层所需要的 Subscriber,然后再将转换后的订阅上层 OnSubscribe,
        可以看做是一个从下至上的转换.
        举个例子,上述的那个例子,将 int 转变成 String :
        ```java
        //map,可以看到,它的转变是从上至下的,上层数据是 int,下层数据是 String, map函数需要实现 int => String 的转换过程
        new Func1<Integer, String>() {
           @Override
           public String call(Integer number) { // 参数类型 int
               return "number " + number; // 返回类型 String
           }
        };
        //lift,可以看到 Operator 是将 String => int 的,和 map 方向相反
        observable.lift(new Observable.Operator<String, Integer>() {
            @Override//这个参数 subscriber,就是最终的,也就是我们使用时传入的
            public Subscriber<? super Integer> call(final Subscriber<? super String> subscriber) {
                return new Subscriber<Integer>() {
                    @Override
                    public void onNext(Integer integer) {
                        subscriber.onNext("number " + integer);
                    }
                    //省略 onCompleted 和 onError函数
                };
            }
        });
        ```
        map 函数的转换可以看做是 数据的正向转换,而 lift 的转换可以看做是 Subscriber 的逆向转换.
        
 4. 线程控制
      - subscribeOn()
        `OperatorSubscribeOn` 新建了一个 OnSubscribe,执行 call() 即产生事件.
      - observeOn()
        使用了 lift 操作符, operator 是 `OperatorObserveOn`.
