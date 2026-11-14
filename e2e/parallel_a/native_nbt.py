"""Bounded read-only Anvil/NBT oracle; never part of the production receipt or recovery path."""
import gzip,io,struct,zlib
from pathlib import Path

class Reader:
    def __init__(self,data):
        if len(data)>64*1024**2:raise ValueError('NBT input bound')
        self.data=io.BytesIO(data);self.nodes=0
    def take(self,n):
        if n<0 or n>64*1024**2:raise ValueError('NBT length bound')
        value=self.data.read(n)
        if len(value)!=n:raise ValueError('Truncated native NBT')
        return value
    def number(self,fmt):return struct.unpack('>'+fmt,self.take(struct.calcsize('>'+fmt)))[0]
    def string(self):return self.take(self.number('H')).decode('utf-8')
    def value(self,kind,depth=0):
        self.nodes+=1
        if depth>128 or self.nodes>1_000_000:raise ValueError('NBT structural bound')
        if kind in (1,2,3,4,5,6):return self.number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
        if kind==8:return self.string()
        if kind in (7,11,12):
            count=self.number('i')
            if count<0 or count>4_000_000:raise ValueError('NBT array bound')
            if kind==7:return self.take(count)
            return [self.number('i' if kind==11 else 'q') for _ in range(count)]
        if kind==9:
            item=self.number('B');count=self.number('i')
            if count<0 or count>1_000_000:raise ValueError('NBT list bound')
            return [self.value(item,depth+1) for _ in range(count)]
        if kind==10:
            result={}
            while True:
                item=self.number('B')
                if item==0:return result
                name=self.string()
                if name in result:raise ValueError('Duplicate native NBT name')
                result[name]=self.value(item,depth+1)
        raise ValueError('Unsupported NBT tag '+str(kind))

def read_nbt(data):
    reader=Reader(data);kind=reader.number('B');reader.string();value=reader.value(kind)
    if reader.data.read(1):raise ValueError('Native NBT trailing bytes')
    return value

def chunk(region,x,z):
    raw=Path(region).read_bytes();index=((x&31)+(z&31)*32)*4
    if len(raw)<8192:raise ValueError('Native region header missing')
    offset=int.from_bytes(raw[index:index+3],'big')*4096;sectors=raw[index+3]
    if not offset:return None
    if offset<8192 or sectors==0 or offset+5>len(raw):raise ValueError('Native region allocation bound')
    size=int.from_bytes(raw[offset:offset+4],'big');kind=raw[offset+4]
    if size<1 or size+4>sectors*4096 or kind&128:raise ValueError('Unsupported/external native chunk')
    # A native RegionFile may end immediately after its last payload. The
    # allocation's unused sector tail need not have been written to disk.
    if offset+4+size>len(raw):raise ValueError('Truncated native chunk payload')
    payload=raw[offset+5:offset+4+size]
    if kind==1:data=gzip.decompress(payload)
    elif kind==2:data=zlib.decompress(payload)
    elif kind==3:data=payload
    else:raise ValueError('Unsupported native compression '+str(kind))
    return read_nbt(data)
