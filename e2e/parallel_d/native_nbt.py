"""Independent bounded reader for actual 26.3 saved-chunk NBT. No native success flags."""
import base64,hashlib,struct,zlib
from common import require,read

def decode(path):
    witness=read(path);raw=base64.b64decode(witness['native_chunk_base64'],validate=True)
    require(hashlib.sha256(raw).hexdigest()==witness['native_chunk_sha256'],'native chunk bytes changed')
    require(len(raw)>5 and int.from_bytes(raw[:4],'big')==len(raw)-4 and raw[4]==2,'native chunk framing')
    inflater=zlib.decompressobj();data=inflater.decompress(raw[5:],2*1024*1024)
    require(inflater.eof and not inflater.unused_data and len(data)<2*1024*1024,'native NBT size/stream')
    cursor=0;tokens=0
    def take(n):
        nonlocal cursor
        require(0<=n<=len(data)-cursor,'truncated native NBT');value=data[cursor:cursor+n];cursor+=n;return value
    def number(fmt):return struct.unpack('>'+fmt,take(struct.calcsize(fmt)))[0]
    def string():return take(number('H')).decode('utf-8')
    def value(kind,depth=0):
        nonlocal tokens
        tokens+=1;require(depth<32 and tokens<1000000,'native NBT bounds')
        if kind in (1,2,3,4,5,6):return number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
        if kind==8:return string()
        if kind==9:
            item=number('B');n=number('i');require(0<=n<=65536,'native list bound');return [value(item,depth+1)for _ in range(n)]
        if kind==10:
            out={}
            while True:
                item=number('B')
                if item==0:return out
                name=string();require(name not in out,'duplicate NBT key');out[name]=value(item,depth+1)
        if kind in (7,11,12):
            n=number('i');require(0<=n<=1048576,'native array bound');return take(n*{7:1,11:4,12:8}[kind])
        raise ValueError('unsupported native NBT tag')
    require(number('B')==10,'native root compound');string();root=value(10)
    require(cursor==len(data),'native chunk trailing mismatch');return root

def saved_entities(path):
    root=decode(path);require(root['xPos']==8 and root['zPos']==8,'native chunk coordinate mismatch');entities=root['block_entities'];require(isinstance(entities,list),'native block entities missing');return entities
