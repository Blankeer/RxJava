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
  
  ![map](http://plantuml.com/plantuml/png/ZLAzJiCm4Dxp51uT2SJEq0N1LYe1Bt1s3gY8xUXijpm-Hzk4EBwW3bdklZz_5yWf-EmCKWCs1L8E6uVgTgDYo6HnPoBI0KoFwbuv63H_JzctJRLcIl2lsKHBUuDR69ZWyQXsnL2dptsy-K-4TuMf9OI4kjHkBo6Nu3XYT0Bwm3HnY1a5bibB2FFPXQT92-ZgSHRwNh1PuCuX2vFVLhRpMQKo5LIB2Q6XwtJX8V64SsOVuHqdu59ZvJWhVKdeo-mlozYQx3J3hP2xe8w1lcH2dUqPlvD-EzrojZb3kTunZOGG_usJtajxhVr3S4EF2JlTtWN_I1H8uxLUA-Jc1m00)
  
1. 调用 map(mapFun)
2. map 方法内部实例化 OnSubscribeMap ,传入 this (Observable) 和 mapFun.
3. 调用 Observable.create 方法,生成新的 MapObservable
4. 我们调用 subscribe 时,实际上调用的是 MapObservable.subscribe().
5. 回调 onStart()
6. 调用 onSubscribeMap 的 call()
7. 生成一个新的 mapSubscriber ,之后会订阅原来的 Observable.
8. 关联两个 subscriber 的 unsubscribe() 
9. 用新的 mapSubscriber 订阅原来的 Observable.
10. 原来的 Observable 回调 mapSubscriber的 onStart()
11. 调用原来的 OnSubscribe.call()
12. OnSubscribe 内部的执行逻辑
13. 调用 mapSubscriber 的 onNext(T)
14. mapSubscriber 会调用 mapFun.call(T) 返回 R
15. mapSubscriber 调用真正的 targetSubscriber.onNext(R),R 是转换后的数据
    
看流程有点复杂,其实也很简单,就是 map 在观察源和观察者之前做了一层转换,当发生订阅时,观察者订阅的不是真正的观察源,
而是 map 内部的'转换观察源','转换观察源'内部会再去订阅真正的观察源,然后将观察源返回的数据通过转换函数`mapFun`转换,
再返回给我们定义的观察者.
    
        
  - lift 变换
  
    ```java
    new Func1<Integer, String>() {
                @Override
                public String call(Integer number) { // 参数类型 int
                    return "number " + number; // 返回类型 String
                }
            };
            Observable<Integer> observable = Observable.just(1, 2, 3, 4, 5);
            observable.lift(new Observable.Operator<String, Integer>() {
                @Override//这个参数 subscriber,就是最终的,也就是我们使用时传入的
                public Subscriber<? super Integer> call(final Subscriber<? super String> subscriber) {
                    return new Subscriber<Integer>() {
                        @Override
                        public void onNext(Integer integer) {
                            subscriber.onNext("number " + integer);
                        }
    
                        @Override
                        public void onCompleted() {
    
                        }
    
                        @Override
                        public void onError(Throwable e) {
    
                        }
                    };
                }
            }).subscribe(new Action1<String>() {
                @Override
                public void call(String s) {
                    Log.d(s);
                }
            });
    ```
    
    这个例子功能和上面 map 的例子相同,都是将数字变为字符串.
    
    ![lift](http://plantuml.com/plantuml/png/ZPAz3e8m4CTtdg9ZIGHt1nVZPYHHNw2jfo4MIev3VNoBKgLG32FNt_-ZFKH3fcmA2HW9HGPP0L41B5apKm6ATh9Y9JnOrdtoD4tZ3gbDWmeoMGFUXTHWKPrVY3jf78s8rhWyDh9Mc4lOMThv-rFUenR__Tf8huD240HyaEDAZ_3QKPm4Ne6-AfitWAryo5W36sIdn4Fu-B9AQ1r86eB-1gP4ZaY2KMsT7nwAy0dlBW6wU_tVWMlcstpNr0Cdr6V37WPlOK6Wt1wUn4-VX-uTJUtbZH_YsVORKrGQ2CuyNffoYsZ_TvYTDg5-0G00)
    
    整体流程和 map 类似,都是生成一个中间 Subscriber ,去订阅原来的 Observable,
    然后在 onNext 等方法里将数据处理转换之后,回调真正的 Subscriber.
    
    
### 线程控制
      - subscribeOn()
        `OperatorSubscribeOn` 新建了一个 OnSubscribe,执行 call() 即产生事件.即 OperatorSubscribeOn,内部调用了线程相关.
      - observeOn()
        使用了 lift 操作符, operator 是 `OperatorObserveOn`.内部也是线程相关代码.
