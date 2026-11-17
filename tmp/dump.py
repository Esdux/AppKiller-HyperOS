import struct,sys
d=open(sys.argv[1],'rb').read()
print('len',len(d))
pos=0
n=0
while pos+8<=len(d) and n<8:
    ctype,hsize,csize=struct.unpack_from('<HHI',d,pos)
    print('chunk at',pos,'type=0x%04x'%ctype,'hsize',hsize,'csize',csize)
    if ctype==0x0001:
        print('  strcount,stylecount,flags,strstart,stylestart',struct.unpack_from('<IIIII',d,pos+hsize))
    if csize<=0: break
    pos+=csize; n+=1