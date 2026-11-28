import re,sys,json,os
# parse tr("zh", "en") with Kotlin string literals (handles escapes, ${} with nested braces/quotes roughly)
def lit(s,i):
    assert s[i]=='"'
    if s.startswith('"""',i):
        j=s.index('"""',i+3); return s[i+3:j], j+3
    j=i+1; out=[]
    while True:
        c=s[j]
        if c=='\\': out.append(s[j:j+2]); j+=2; continue
        if c=='$' and s[j+1]=='{':
            d=0; k=j+1
            while True:
                ch=s[k]
                if ch=='{': d+=1
                elif ch=='}':
                    d-=1
                    if d==0: break
                elif ch=='"':
                    _,k=lit(s,k); continue
                k+=1
            out.append(s[j:k+1]); j=k+1; continue
        if c=='"': return ''.join(out), j+1
        out.append(c); j+=1
def ws(s,i):
    while s[i] in ' \n\t\r': i+=1
    return i
res={}
for root in sys.argv[1:]:
  for dp,_,fs in os.walk(root):
    for f in fs:
      if not f.endswith('.kt'): continue
      p=os.path.join(dp,f); s=open(p).read()
      for m in re.finditer(r'(?<![A-Za-z0-9_.])tr\(',s):
        i=ws(s,m.end())
        if s[i]!='"': continue
        try:
          zh,i=lit(s,i); i=ws(s,i)
          if s[i]!=',': continue
          i=ws(s,i+1)
          if s[i]!='"': continue
          en,i=lit(s,i); i=ws(s,i)
          if s[i]!=')': continue
        except Exception as e: continue
        res.setdefault(en,{"zh":zh,"files":set()})["files"].add(os.path.relpath(p,root))
out={k:{"zh":v["zh"]} for k,v in res.items()}
json.dump(out,sys.stdout,ensure_ascii=False,indent=0)
