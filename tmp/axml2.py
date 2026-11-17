import struct, sys
data = open(sys.argv[1],'rb').read()
assert struct.unpack_from('<H', data, 0)[0] == 0x0003, 'not axml'
pos = 8
strings = []
resmap = []
nodes = []
while pos + 8 <= len(data):
    ctype, hsize, csize = struct.unpack_from('<HHI', data, pos)
    if csize <= 0 or pos + csize > len(data): break
    body = pos + hsize
    if ctype == 0x0001:  # string pool
        strcount, stylecount, flags, strstart, stylestart = struct.unpack_from('<IIIII', data, body)
        utf8 = (flags & 0x100) != 0
        offs = [struct.unpack_from('<I', data, body + 28 + 4*i)[0] for i in range(strcount)]
        for o in offs:
            p = body + strstart + o
            if utf8:
                n = data[p]; p += 1
                if n & 0x80: p += 1
                n2 = data[p]; p += 1
                if n2 & 0x80:
                    n2 = ((n2 & 0x7f) << 8) | data[p]; p += 1
                strings.append(data[p:p+n2].decode('utf-8','replace'))
            else:
                n = struct.unpack_from('<H', data, p)[0]; p += 2
                if n & 0x8000:
                    n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', data, p)[0]; p += 2
                strings.append(data[p:p+n*2].decode('utf-16-le','replace'))
    elif ctype == 0x0180:  # resource map
        resmap = [struct.unpack_from('<I', data, body + 4*i)[0] for i in range((csize - hsize)//4)]
    elif ctype == 0x0102:  # START_ELEMENT
        ns, name = struct.unpack_from('<iI', data, body)
        attrstart, attrsize, attrcount = struct.unpack_from('<HHH', data, body + 8)
        attrs = []
        for i in range(attrcount):
            ap = body + attrstart + attrsize * i
            ans, aname, araw, atyp, adata = struct.unpack_from('<iIiBB', data, ap)
            aname = aname & 0xFFFFFFFF
            rid = resmap[aname] if aname < len(resmap) else 0
            nm = strings[aname] if aname < len(strings) else '?'
            if atyp == 0x03:
                val = strings[adata] if adata < len(strings) else ''
            elif atyp == 0x10:
                val = adata
            elif atyp == 0x12:
                val = adata != 0
            else:
                val = adata
            attrs.append((nm, rid, val))
        nodes.append((strings[name] if name < len(strings) else '?', attrs))
    pos += csize
PROC_RID = 0x01010011
pkg = ''
procs = set()
for tag, attrs in nodes:
    for nm, rid, val in attrs:
        if rid == 0x0101020c and isinstance(val, str):
            pkg = val
        if rid == PROC_RID and isinstance(val, str) and val:
            procs.add(val)
import collections
names=collections.Counter()
for tag, attrs in nodes:
    for nm, rid, val in attrs:
        names[(nm, hex(rid))]+=1
for k,v in names.most_common(60): print(k,v)
print('pkg =', pkg)
print('nodes =', len(nodes))
for p in sorted(procs): print(' ', p)
print('total', len(procs))