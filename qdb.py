import sqlite3
con = sqlite3.connect('app_db.raw')
cur = con.cursor()
print('tables:', [r[0] for r in cur.execute("select name from sqlite_master where type='table'")])
print('--- media schema ---')
for r in cur.execute("select sql from sqlite_master where name='media'"):
    print(r[0])
print('--- media rows ---')
for r in cur.execute("select id, post_row_id, media_index, media_type, file_path, thumb_path, ext, source_url from media"):
    print(r)