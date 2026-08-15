import sqlite3,tempfile,os
N=120_000
with tempfile.TemporaryDirectory() as td:
 p=os.path.join(td,'s.db'); db=sqlite3.connect(p)
 db.execute('CREATE TABLE files(id INTEGER PRIMARY KEY, filename TEXT, junk TEXT)')
 db.executemany('INSERT INTO files VALUES(?,?,?)',((i,f'random_file_{i:07d}_archive.bin','x'*30) for i in range(N)))
 db.commit(); db.execute('VACUUM'); db.commit(); base=os.path.getsize(p)
 db.execute('CREATE TABLE files_search_norm(id INTEGER PRIMARY KEY, filename_norm TEXT NOT NULL)')
 db.execute("INSERT INTO files_search_norm SELECT id, lower(replace(replace(replace(filename,'.',' '),'-',' '),'_',' ')) FROM files")
 db.commit(); db.execute('VACUUM'); db.commit(); norm=os.path.getsize(p)
 print('base_mb=%.2f'%(base/1048576)); print('with_norm_mb=%.2f'%(norm/1048576)); print('norm_overhead_pct=%.1f'%((norm/base-1)*100))
 try:
  db.execute("CREATE VIRTUAL TABLE ft USING fts5(n,content='',tokenize='trigram')")
  db.execute('INSERT INTO ft(rowid,n) SELECT id,filename_norm FROM files_search_norm'); db.commit(); db.execute('VACUUM'); db.commit(); fts=os.path.getsize(p)
  print('with_norm_plus_fts_mb=%.2f'%(fts/1048576)); print('fts_increment_pct=%.1f'%((fts/norm-1)*100))
 except Exception as e: print(e)
