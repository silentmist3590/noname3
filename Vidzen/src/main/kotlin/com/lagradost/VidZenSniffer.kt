private const val DPAD_JS = """
(function(){
  if(window.__dpadInit)return;window.__dpadInit=true;
  var c=document.createElement('div');c.id='__dpad_cursor';
  c.style.cssText='position:fixed;width:22px;height:22px;border-radius:50%;background:rgba(255,60,60,0.85);border:2px solid #fff;box-shadow:0 0 10px rgba(255,60,60,0.9);z-index:2147483647;pointer-events:none;display:none;transition:left .08s,top .08s,opacity .3s;';
  document.body.appendChild(c);
  window.__dpadX=window.innerWidth/2;window.__dpadY=window.innerHeight/2;
  window.__hideTimer=null;
  
  function showCursor(){
    c.style.display='block';
    c.style.opacity='1';
    if(window.__hideTimer)clearTimeout(window.__hideTimer);
    window.__hideTimer=setTimeout(function(){
      c.style.opacity='0';
      setTimeout(function(){c.style.display='none';},300);
    },3000);
  }
  
  function render(){
    c.style.left=window.__dpadX+'px';
    c.style.top=window.__dpadY+'px';
    showCursor();
  }
  
  window.__dpadMove=function(dx,dy){
    window.__dpadX=Math.max(0,Math.min(window.innerWidth-22,window.__dpadX+dx));
    window.__dpadY=Math.max(0,Math.min(window.innerHeight-22,window.__dpadY+dy));
    render();
  };
  
  window.__dpadClick=function(){
    var el=document.elementFromPoint(window.__dpadX+11,window.__dpadY+11);
    if(!el)return;
    if(el.focus)el.focus();
    if(el.click)el.click();
    ['mousedown','mouseup','click'].forEach(function(t){try{el.dispatchEvent(new MouseEvent(t,{bubbles:true}));}catch(e){}});
    showCursor();
  };
  
  // Ẩn cursor ban đầu
  c.style.display='none';
})();
"""
