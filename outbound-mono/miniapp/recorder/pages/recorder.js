const {origin} = require('../config');
const api = (path,method='GET',data) => new Promise((resolve,reject)=>{
  if(!/^https:\/\/[a-z0-9.-]+(?::\d+)?$/i.test(origin))return reject(Error('请先配置本企业已登记的 HTTPS 服务域名'));
  wx.request({url:origin+path,method,data,header:getApp().globalData.token?{Authorization:'Bearer '+getApp().globalData.token}:{},
    success:r=>r.statusCode>=200&&r.statusCode<300?resolve(r.data):reject(Error(r.data.message||'请求失败')),fail:e=>reject(Error(e.errMsg))});
});
Page({
 data:{username:'',password:'',script:'',rows:[],index:0,logged:false,busy:false,recording:false,file:'',message:'登录后选择话术，录音将回传至本企业后台。'},
 onLoad(options){if(/^\d+$/.test(options.script||''))this.setData({script:options.script});this.rec=wx.getRecorderManager();
 this.rec.onStop(v=>{if(!this.closed)this.setData({recording:false,file:v.tempFilePath});});this.rec.onError(e=>{if(!this.closed)this.setData({recording:false,message:e.errMsg});});
 this.player=wx.createInnerAudioContext();},
 onUnload(){this.closed=true;if(this.data.recording)this.rec.stop();this.player.destroy();getApp().globalData.token='';},
 userInput(e){this.setData({username:e.detail.value});},passwordInput(e){this.setData({password:e.detail.value});},
 scriptInput(e){this.setData({script:e.detail.value,rows:[],file:'',index:0});},
 async run(f){if(this.data.busy)return;this.setData({busy:true});try{await f();}catch(e){this.setData({message:e.message});}finally{this.setData({busy:false});}},
 login(){return this.run(async()=>{const v=await api('/api/v1/auth/login','POST',{username:this.data.username,password:this.data.password});
 if(!['admin','operator'].includes(v.role))throw Error('需要运营或管理员账号');getApp().globalData.token=v.access_token;this.setData({logged:true,password:'',message:'登录成功'});});},
 async read(){if(!/^[1-9]\d*$/.test(this.data.script))throw Error('请输入正确的话术 ID');const rows=await api('/api/v1/workspace/scripts/'+this.data.script+'/audio');this.setData({rows,file:'',index:0,message:'已加载 '+rows.length+' 个片段'});},
 load(){return this.run(()=>this.read());},choose(e){this.setData({index:Number(e.detail.value),file:''});},
 start(){if(this.data.recording||!this.data.rows.length)return;this.player.stop();this.setData({recording:true,file:''});this.rec.start({duration:60000,format:'aac',sampleRate:16000,numberOfChannels:1,encodeBitRate:48000});},
 stop(){this.rec.stop();},listen(){this.player.src=this.data.file;this.player.play();},
 upload(){return this.run(async()=>{const r=this.data.rows[this.data.index];if(!r||!this.data.file)throw Error('请先录音');
 await new Promise((resolve,reject)=>wx.uploadFile({url:origin+'/api/v1/workspace/scripts/'+this.data.script+'/audio/'+r.key,filePath:this.data.file,name:'audio',formData:{revision:String(r.revision)},header:{Authorization:'Bearer '+getApp().globalData.token},success:v=>v.statusCode>=200&&v.statusCode<300?resolve():reject(Error('保存失败，请重新载入片段后再试')),fail:e=>reject(Error(e.errMsg))}));
 await this.read();this.setData({message:'保存成功，可在网页查看覆盖率和试听'});});},
 logout(){return this.run(async()=>{await api('/api/v1/auth/logout','POST');getApp().globalData.token='';this.setData({logged:false,password:'',file:'',rows:[]});});}
});
