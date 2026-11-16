package io.github.yudigaga.lightfreeze.ui;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.UUID;
public class ExportDraftTest {
 @Test public void roundTripSurvivesNewInstance() throws Exception {
  File d=Files.createTempDirectory("export-test").toFile(); String id=UUID.randomUUID().toString();
  new ExportDraft("{中文}","tags.json","summary").save(d,id);
  ExportDraft v=ExportDraft.load(d,id); assertEquals("{中文}",v.json); assertEquals("tags.json",v.filename);
  ExportDraft.remove(d,id); assertEquals(0,d.list().length); d.delete();
 }
 @Test public void rejectsTraversal() throws Exception {
  try { ExportDraft.load(new File("."),"../outside"); fail(); } catch(IllegalArgumentException expected) {}
 }
 @Test public void rejectsOversize() throws Exception {
  File d=Files.createTempDirectory("export-limit").toFile();
  try { new ExportDraft("a".repeat(ExportDraft.MAX_BYTES+1),"a","b").save(d,UUID.randomUUID().toString()); fail(); }
  catch(IOException expected) {} assertEquals(0,d.list().length); d.delete();
 }
 @Test public void rejectsTruncatedDraft() throws Exception {
  File d=Files.createTempDirectory("export-corrupt").toFile(); String id=UUID.randomUUID().toString();
  Files.write(new File(d,id+".draft").toPath(),new byte[]{0,0,0,1});
  try { ExportDraft.load(d,id); fail(); } catch(IOException expected) {}
  ExportDraft.remove(d,id); d.delete();
 }

 @Test public void rejectsMalformedUtf8() throws Exception {
  File d=Files.createTempDirectory("export-utf8").toFile(); String id=UUID.randomUUID().toString();
  try(DataOutputStream out=new DataOutputStream(new FileOutputStream(new File(d,id+".draft")))) {
   out.writeInt(1); out.writeUTF("a"); out.writeUTF("b"); out.writeInt(1); out.write(0xff);
  }
  try { ExportDraft.load(d,id); fail(); } catch(IOException expected) {}
  ExportDraft.remove(d,id); d.delete();
 }
 @Test public void rejectsTrailingBytes() throws Exception {
  File d=Files.createTempDirectory("export-tail").toFile(); String id=UUID.randomUUID().toString();
  new ExportDraft("{}","a","b").save(d,id);
  try(FileOutputStream out=new FileOutputStream(new File(d,id+".draft"),true)) { out.write(1); }
  try { ExportDraft.load(d,id); fail(); } catch(IOException expected) {}
  ExportDraft.remove(d,id); d.delete();
 }
 @Test public void cancellationAndRepeatedRemovalAreSafe() throws Exception {
  File d=Files.createTempDirectory("export-cancel").toFile(); String id=UUID.randomUUID().toString();
  new ExportDraft("{}","a","b").save(d,id); ExportDraft.remove(d,id); ExportDraft.remove(d,id);
  assertEquals(0,d.list().length); d.delete();
 }
 @Test public void fullCachePreservesRecentRequests() throws Exception {
  File d=Files.createTempDirectory("export-cap").toFile();
  for(int i=0;i<16;i++) new ExportDraft("{}","a","b").save(d,UUID.randomUUID().toString());
  try { new ExportDraft("{}","a","b").save(d,UUID.randomUUID().toString()); fail(); } catch(IOException expected) {}
  assertEquals(16,d.list().length); for(File f:d.listFiles()) f.delete(); d.delete();
 }
}
