import sqlite3,tempfile,os,time,statistics,random
N=120_000
repls=[
('أ','ا'),('إ','ا'),('آ','ا'),('ٱ','ا'),('ى','ي'),('ی','ي'),('ک','ك'),('ـ',''),
('ً',''),('ٌ',''),('ٍ',''),('َ',''),('ُ',''),('ِ',''),('ّ',''),('ْ',''),('ٰ',''),('ٔ',''),('ٕ',''),
('٠','0'),('١','1'),('٢','2'),('٣','3'),('٤','4'),('٥','5'),('٦','6'),('٧','7'),('٨','8'),('٩','9'),
('۰','0'),('۱','1'),('۲','2'),('۳','3'),('۴','4'),('۵','5'),('۶','6'),('۷','7'),('۸','8'),('۹','9'),
('.',' '),('_',' '),('-',' '),('–',' '),('—',' '),(',',' '),('،',' '),(';',' '),('؛',' '),(':',' '),
('(',' '),(')',' '),('[',' '),(']',' '),('{',' '),('}',' '),('/',' '),('\\',' '),('+',' '),('=',' ')]

def sql_expr(col):
    e=f'LOWER({col})'
    for a,b in repls:
        e=f"REPLACE({e},'{a.replace(chr(39),chr(39)*2)}','{b.replace(chr(39),chr(39)*2)}')"
    for _ in range(3): e=f"REPLACE({e},'  ',' ')"
    return f'TRIM({e})'

with tempfile.TemporaryDirectory() as td:
    db=sqlite3.connect(os.path.join(td,'q.db'))
    db.execute('CREATE TABLE files(id INTEGER PRIMARY KEY, filename TEXT)')
    rows=[]
    for i in range(N):
        if i%20000==0: name=f'أرشيف.course-{i}_word1.word2.mp4'
        elif i%17000==0: name=f'word1-word2_special_{i}.mkv'
        else: name=f'random_file_{i:07d}_archive.bin'
        rows.append((i,name))
        if len(rows)==5000: db.executemany('INSERT INTO files VALUES(?,?)',rows); rows=[]
    if rows: db.executemany('INSERT INTO files VALUES(?,?)',rows)
    db.commit()
    expr=sql_expr('filename')
    q='word1 word2'.split()
    sql='SELECT COUNT(*) FROM files WHERE '+ ' AND '.join([f'{expr} LIKE ?' for _ in q])
    args=[f'%{x}%' for x in q]
    db.execute(sql,args).fetchone()
    raw=[]; norm=[]
    for _ in range(5):
        t=time.perf_counter(); db.execute("SELECT COUNT(*) FROM files WHERE filename LIKE '%word1%' AND filename LIKE '%word2%'").fetchone(); raw.append((time.perf_counter()-t)*1000)
        t=time.perf_counter(); db.execute(sql,args).fetchone(); norm.append((time.perf_counter()-t)*1000)
    print(f'rows={N}')
    print(f'raw_like_median_ms={statistics.median(raw):.1f}')
    print(f'tolerant_nested_replace_median_ms={statistics.median(norm):.1f}')
    try:
        db.execute("CREATE VIRTUAL TABLE ft USING fts5(n,content='',tokenize='trigram')")
        db.execute("INSERT INTO ft(rowid,n) SELECT id, lower(replace(replace(replace(filename,'.',' '),'-',' '),'_',' ')) FROM files")
        db.commit(); times=[]
        for _ in range(10):
            t=time.perf_counter(); db.execute("SELECT COUNT(*) FROM ft WHERE ft MATCH ?",('"word1" AND "word2"',)).fetchone(); times.append((time.perf_counter()-t)*1000)
        print(f'fts5_trigram_median_ms={statistics.median(times):.3f}')
    except Exception as e:
        print('fts5_trigram_unavailable=',e)
