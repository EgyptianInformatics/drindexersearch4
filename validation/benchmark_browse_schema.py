import sqlite3, tempfile, os, time, statistics
N_FILES=300_000
N_FOLDERS=10_000
with tempfile.TemporaryDirectory() as td:
    p=os.path.join(td,'bench.db'); db=sqlite3.connect(p)
    db.execute('PRAGMA journal_mode=OFF'); db.execute('PRAGMA synchronous=OFF')
    db.execute('CREATE TABLE files(id INTEGER PRIMARY KEY,scan_id INTEGER,folder_id INTEGER,filename TEXT)')
    db.execute('CREATE INDEX idx_files_scan ON files(scan_id)')
    db.execute('CREATE TABLE folders(id INTEGER PRIMARY KEY,scan_id INTEGER,root_id INTEGER,parent_id INTEGER,path TEXT,name TEXT)')
    db.execute('CREATE INDEX idx_folders_parent ON folders(scan_id,root_id,parent_id)')
    db.executemany('INSERT INTO folders VALUES(?,?,?,?,?,?)', ((i,1,1,None if i<100 else i//100,f'f/{i}',f'folder{i}') for i in range(1,N_FOLDERS+1)))
    batch=[]
    for i in range(1,N_FILES+1):
        batch.append((i,1,(i%N_FOLDERS)+1,f'file{i}.bin'))
        if len(batch)==5000:
            db.executemany('INSERT INTO files VALUES(?,?,?,?)',batch); batch.clear()
    if batch: db.executemany('INSERT INTO files VALUES(?,?,?,?)',batch)
    db.commit()

    def old():
        return db.execute('SELECT DISTINCT folder_id FROM files WHERE scan_id=?',(1,)).fetchall()
    def direct():
        return db.execute('SELECT id,name,path FROM folders WHERE scan_id=? AND root_id=? AND parent_id IS NULL ORDER BY name COLLATE NOCASE',(1,1)).fetchall()
    old(); direct()
    ot=[]; dt=[]
    for _ in range(15):
        t=time.perf_counter(); old(); ot.append((time.perf_counter()-t)*1000)
        t=time.perf_counter(); direct(); dt.append((time.perf_counter()-t)*1000)
    print(f'old_distinct_median_ms={statistics.median(ot):.3f}')
    print(f'v3_direct_children_median_ms={statistics.median(dt):.3f}')
    print(f'speedup={statistics.median(ot)/statistics.median(dt):.1f}x')
