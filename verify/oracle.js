// 用站点自己的 crypto-js 原文，算出期望值 —— 这就是独立 oracle
const CryptoJS = require('/tmp/zwverify/crypto-js.min.js');

// 1) I.decrypt(hmacKey)：bundle 里的原文
const keyWA = CryptoJS.enc.Utf8.parse('server_date_time');
const ivWA  = CryptoJS.enc.Utf8.parse('client_date_time');
const plain = CryptoJS.AES.decrypt('vECLlcxq3mdtoIOCKdF/Gg==', keyWA,
                   { iv: ivWA, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.Pkcs7 })
                 .toString(CryptoJS.enc.Utf8);

// 2) 固定输入下的签名
const RID = '11111111-2222-4333-8444-555555555555';
const TS  = 1757856123456;
const msg = 'seat::' + RID + '::' + TS + '::' + 'POST';
const sig = CryptoJS.HmacSHA256(msg, plain).toString();

console.log(JSON.stringify({ key: plain, msg, sig }, null, 2));
