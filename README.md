# 大疆 dji-sdk 实现声网agora 推流 

[web体验demo](https://download.agora.io/sdk/release/Agora_Web_SDK_v4_14_0_FULL.zip) 

# 1、运行demo
  ***1. 申请到 声网测试的 appid，token，channel ，准备一下***
  ***2. 打开web体验demo，index.html 填写上面的参数***
  ***3. 打开 DefaultLayoutActivity，  AgoraUtil.getInstance().initEngine 填写上面的参数***
  ***4. 运行web + app demo。app 这边进入（DefaultLayout）***

# 2、注意，无人机这边要先有图传推流，才能触发声网推流。

# 3、AgoraUtil 就是声网相关操作。