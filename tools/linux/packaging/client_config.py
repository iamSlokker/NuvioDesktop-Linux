"""Read public defaults and verify the actual generated constants in application jars.

No application classes are loaded, and no backend/user configuration is accessed.
"""
import io
import pathlib
import struct
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
CONFIG_CLASS = 'com/nuvio/app/core/network/SupabaseConfig.class'


def public_defaults():
    values = dict(line.split('=', 1) for line in (HERE / 'public-client.properties').read_text().splitlines()
                  if line and not line.startswith('#'))
    if (set(values) != {'SUPABASE_URL', 'SUPABASE_ANON_KEY'}
            or values['SUPABASE_URL'] != 'https://api.nuvio.tv'
            or not values['SUPABASE_ANON_KEY'].startswith('sb_publishable_')):
        raise ValueError('Official packages require the public cloud URL and a publishable client key')
    return {'URL': values['SUPABASE_URL'], 'ANON_KEY': values['SUPABASE_ANON_KEY']}


def string_fields(data):
    """Read String ConstantValue fields, rather than searching arbitrary pool strings."""
    stream = io.BytesIO(data)

    def read(size):
        value = stream.read(size)
        if len(value) != size:
            raise ValueError('Truncated SupabaseConfig class')
        return value

    def u2():
        return struct.unpack('>H', read(2))[0]

    def u4():
        return struct.unpack('>I', read(4))[0]

    if read(4) != b'\xca\xfe\xba\xbe':
        raise ValueError('Invalid SupabaseConfig class')
    read(4)  # minor/major version
    count, pool, index = u2(), {}, 1
    while index < count:
        tag = read(1)[0]
        if tag == 1:
            # Kotlin metadata uses modified UTF-8; only the ASCII public fields
            # are inspected. Metadata is never interpreted as configuration.
            pool[index] = read(u2()).decode('utf-8', errors='replace')
        elif tag == 8:
            pool[index] = u2()
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            read(4)
        elif tag in (5, 6):
            read(8)
            index += 1
        elif tag in (7, 16, 19, 20):
            read(2)
        elif tag == 15:
            read(3)
        else:
            raise ValueError('Unsupported class constant tag: ' + str(tag))
        index += 1
    read(6)  # access, this, super
    read(2 * u2())  # interfaces
    fields = {}
    for _ in range(u2()):
        access, name, descriptor, attributes = u2(), pool[u2()], pool[u2()], u2()
        for _ in range(attributes):
            attribute, payload = pool[u2()], read(u4())
            if attribute == 'ConstantValue' and descriptor == 'Ljava/lang/String;' and access & 0x18 == 0x18:
                fields[name] = pool[pool[struct.unpack('>H', payload)[0]]]
    return fields


def verify_packaged_backend(image):
    found = []
    for path in sorted((image / 'lib/app').glob('*.jar')):
        with zipfile.ZipFile(path) as jar:
            for name in jar.namelist():
                if name == CONFIG_CLASS:
                    found.append(string_fields(jar.read(name)))
    if len(found) != 1 or found[0] != public_defaults():
        raise ValueError('Packaged official backend defaults differ from reviewed public client configuration')
