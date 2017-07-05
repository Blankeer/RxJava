RxJava 1.x 源码分析
========
## Demo 分析
最简单的 demo
```java
Observable.OnSubscribe sourceOnSubscribe = new Observable.OnSubscribe<String>() {
    @Override
    public void call(Subscriber<? super String> subscriber) {
        subscriber.onNext("Hello");
        subscriber.onNext("Hi");
        subscriber.onCompleted();
    }
};
Observable sourceObservable = Observable.create(sourceOnSubscribe);
Subscriber<String> targetSubscriber = new Subscriber<String>() {
    @Override
    public void onNext(String s) {
        Log.d(tag, s);
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
sourceObservable.subscribe(targetSubscriber);
```
没有进行简写,每个中间变量都赋予了名字,方便后面说明.

demo的输出结果就是 Hello Hi Completed!
 
## 基本 demo 的函数调用链
分析上述最简单的 demo, 分析中不看 部分与性能、兼容性、扩展性有关的代码和函数调用,仅关注核心代码和调用,
在相关源码里,关键部分有相关注释说明.

先上图.

![demo](http://plantuml.com/plantuml/png/bP0n3i8m34Ntd29ZCb1s1XR4IeToWIIr42A9aUjGZa-1gereEh1-_UTdsoJ6c8854iOnaWmW0iZDNdQOo44TcsGxHrSBSYSZz39BL5LLUgpNjWDw6ElVFKTW6DHYX1PPRNRaw4Sn1QKHNnyRkW1FCOte7EJB5JpTTCJl92qMzR8FOpEahCf0wN_EUB_kowehV8koHxgLWUA69tYoEki_Y0E6kmU6LkajnYCHaXg-_W80)

1. 实例化 Observable.OnSubscribe , 记为 sourceOnSubscribe.
2. 调用 Observable.create 静态方法
3. create()内部会实例化 Observable 对象,需要传入sourceOnSubscribe,将其返回值记为 sourceObservable.
4. 实例化 Subscriber , 记为 targetSubscriber.
5. 调用 sourceObservable.subscribe(targetSubscriber) 方法,这是将观察者和观察源建立联系的地方,订阅.
6. sourceObservable 会首先调用 targetSubscriber.onStart() 方法.
7. sourceObservable 调用 sourceOnSubscribe 的 call(targetSubscriber)方法, 就是上面我们自定义的地方,执行到我们写的代码附近了.
8. 执行相关逻辑,上面 demo 中什么都没做,这里需要我们自己实现具体逻辑.
9. 调用 `targetSubscriber.onNext(T)` 方法,这里也是上面 demo 里自己实现的地方.
10. `targetSubscriber.onCompleted()` or `targetSubscriber.onError(e)`,整个流程跑完了.

整个调用流程其实并不复杂,跟踪下来还是很容易的,RxJava 在内部也没做太多的事.

## 深入使用的源码分析
RxJava 最强大的就是操作符 和 线程操作,接下来看看这部分.
### 操作符
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
          这个例子是把 数字 转变为 "number "+数字.
          分析前首先 将Observable.just(1,2,3,4,5)返回的Observable 记做 sourceObservable ,
           map(mapFun)方法调用后,包装了一个新的OnSubscribeMap,即OnSubscribeMap 它保存了 sourceObservable 和 mapFun(我们自定义的转换函数),
           并生成了一个新的Observable,记做 MapObservable,它的 onSubscribe 就是 OnSubscribeMap,
          当调用subscribe(action1)时,action1 会包装成Subscriber ,记做 targetSubscriber,
          首先会调 OnSubscribeMap.call(targetSubscriber),会生成一个新的 Subscriber,即 MapSubscriber(sourceObservable,mapFun), 记做 parent,
          再调用 targetSubscriber.add(parent) ,这是为了保证调用 targetSubscriber 的unsubscribe()和isUnsubscribed() 能调用到 parent 的对应方法中,
          最后调用 sourceObservable.unsafeSubscribe(parent),这个是 parent 订阅 sourceObservable,当 sourceObservable 调用 onNext()后,
          parent 的onNext()会被调用,它的 onNext()方法首先会调用mapFun()方法将数据转换,将转换后的结果传给targetSubscriber.onNext().
          
          ![map](http://plantuml.com/plantuml/png/ZP1DZi8m38NtEKMMhZHqzYow6GqxG1guWDDw15AJL4u279y2CrLTmiyYYcH_py_FcA9toHfYXNNqh2qfItfMwRK9n0SqBUcvjjX1_nR95MhKk61kaqoeUMzeYLsFEZfEYX1t-_0noALWDhNoeZzr6vCr4qO2AIXowuT_0CgoV9cWnhQ5GSBtArUw_uOI_uKMjP7-OV9AYndOD9SRRvuQYrZ91Vob0RbJYvZJbVkpHUG8mYKFhkWKOLiesScWjwJbzO0vFZVtvuq5lhpjMehKGF7ftJUpPw3Nyn_8r1a0)
          
          说的有点复杂,其实可以把 MapSubscriber 看做是 targetSubscriber 的包装类,它首先会执行 mapFun 将数据转换,再回调 targetSubscriber
        
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
        
### 线程控制
      - subscribeOn()
        `OperatorSubscribeOn` 新建了一个 OnSubscribe,执行 call() 即产生事件.
      - observeOn()
        使用了 lift 操作符, operator 是 `OperatorObserveOn`.
