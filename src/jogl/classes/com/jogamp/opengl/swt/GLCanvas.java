/**
 * Copyright 2011 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 */
package com.jogamp.opengl.swt;

import javax.media.nativewindow.AbstractGraphicsDevice;
import javax.media.nativewindow.NativeSurface;
import javax.media.nativewindow.ProxySurface;
import javax.media.opengl.GL;
import javax.media.opengl.GLAnimatorControl;
import javax.media.opengl.GLAutoDrawable;
import javax.media.opengl.GLCapabilities;
import javax.media.opengl.GLCapabilitiesChooser;
import javax.media.opengl.GLCapabilitiesImmutable;
import javax.media.opengl.GLContext;
import javax.media.opengl.GLDrawable;
import javax.media.opengl.GLDrawableFactory;
import javax.media.opengl.GLEventListener;
import javax.media.opengl.GLException;
import javax.media.opengl.GLProfile;
import javax.media.opengl.GLRunnable;
import javax.media.opengl.Threading;

import jogamp.opengl.GLContextImpl;
import jogamp.opengl.GLDrawableHelper;
import jogamp.opengl.ThreadingImpl;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.jogamp.common.GlueGenVersion;
import com.jogamp.common.util.VersionUtil;
import com.jogamp.nativewindow.swt.SWTAccessor;
import com.jogamp.opengl.JoglVersion;

/**
 * Native SWT Canvas implementing GLAutoDrawable
 * <p>
 * FIXME: Still needs AWT for threading impl.,
 *        ie. will issue a 'wrong thread' error if runs in headless mode!
 * </p>
 * <p>
 * FIXME: If this instance runs in multithreading mode, see {@link Threading#isSingleThreaded()} (impossible), 
 *        proper recursive locking is required for drawable/context @ destroy and display. 
 *        Recreation etc could pull those instances while animating!
 *        Simply locking before using drawable/context offthread
 *        would allow a deadlock situation!
 * </p>
 * <p>
 * NOTE: [MT-0] Methods utilizing [volatile] drawable/context are not synchronized.
         In case any of the methods are called outside of a locked state
         extra care should be added. Maybe we shall expose locking facilities to the user.
         However, since the user shall stick to the GLEventListener model while utilizing
         GLAutoDrawable implementations, she is safe due to the implicit locked state.
 * </p>
 * <p>
 * FIXME: [MT-2] Revise threading code
          The logic whether to spawn off the GL task and 
          determination which thread to use is too complex and redundant.
          (See isRenderThread(), runInGLThread() and runInDesignatedGLThread())
 * </p>
 */
public class GLCanvas extends Canvas implements GLAutoDrawable {

   /*
    * Flag for whether the SWT thread should be used for OpenGL calls when in single-threaded mode. This is controlled
    * by the setting of the threading mode to worker (do not use SWT thread), awt (use SWT thread), or false (always use
    * calling thread).
    *
    * @see Threading
    *
    * Now done dynamically to avoid early loading of gluegen library.
    */
   //private static final boolean useSWTThread = ThreadingImpl.getMode() != ThreadingImpl.WORKER;

   /* GL Stuff */
   private final GLDrawableHelper helper = new GLDrawableHelper();
   private volatile GLDrawable drawable; // volatile avoids locking all accessors. FIXME still need to sync destroy/display
   private GLContext context;

   /* Native window surface */
   private AbstractGraphicsDevice device;
   private final long nativeWindowHandle;
   private final ProxySurface proxySurface;

   /* Construction parameters stored for GLAutoDrawable accessor methods */
   private int additionalCtxCreationFlags = 0;

   private final GLCapabilitiesImmutable glCapsRequested;

   /* Flag indicating whether an unprocessed reshape is pending. */
   private volatile boolean sendReshape;

   /*
    * Invokes init(...) on all GLEventListeners. Assumes context is current when run.
    */
   private final Runnable initAction = new Runnable() {
      @Override
      public void run() {
         helper.init(GLCanvas.this);
      }
   };

   /*
    * Action to handle display in OpenGL, also processes reshape since they should be done at the same time.
    *
    * Assumes GLContext is current when run.
    */
   private final Runnable displayAction = new Runnable() {
      @Override
      public void run() {
         if (sendReshape) {
            helper.reshape(GLCanvas.this, 0, 0, getWidth(), getHeight());
            sendReshape = false;
         }
         helper.display(GLCanvas.this);
      }
   };

   /* Action to make specified context current prior to running displayAction */
   private final Runnable makeCurrentAndDisplayAction = new Runnable() {
      @Override
      public void run() {
         helper.invokeGL(drawable, context, displayAction, initAction);
      }
   };

   /* Swaps buffers, assuming the GLContext is current */
   private final Runnable swapBuffersAction = new Runnable() {
      @Override
      public void run() {
         drawable.swapBuffers();
      }
   };

   /* Swaps buffers, making the GLContext current first */
   private final Runnable makeCurrentAndSwapBuffersAction = new Runnable() {
      @Override
      public void run() {
         helper.invokeGL(drawable, context, swapBuffersAction, initAction);
      }
   };

   /*
    * Disposes of OpenGL resources
    */
   private final Runnable postDisposeGLAction = new Runnable() {
      @Override
      public void run() {
         context = null;
         if (null != drawable) {
            drawable.setRealized(false);
            drawable = null;
         }
      }
   };

   private final Runnable disposeOnEDTGLAction = new Runnable() {
      @Override
      public void run() {
         helper.disposeGL(GLCanvas.this, drawable, context, postDisposeGLAction);
      }
   };

   private final Runnable disposeGraphicsDeviceAction = new Runnable() {
      @Override
      public void run() {
         if (null != device) {
            device.close();
            device = null;
         }
      }
   };

   /**
    * Storage for the client area rectangle so that it may be accessed from outside of the SWT thread.
    */
   private volatile Rectangle clientArea;

   /**
    * Creates a new SWT GLCanvas.
    *
    * @param parent
    *           Required (non-null) parent Composite.
    * @param style
    *           Optional SWT style bit-field. The {@link SWT#NO_BACKGROUND} bit is set before passing this up to the
    *           Canvas constructor, so OpenGL handles the background.
    * @param caps
    *           Optional GLCapabilities. If not provided, the default capabilities for the default GLProfile for the
    *           graphics device determined by the parent Composite are used. Note that the GLCapabilities that are
    *           actually used may differ based on the capabilities of the graphics device.
    * @param chooser
    *           Optional GLCapabilitiesChooser to customize the selection of the used GLCapabilities based on the
    *           requested GLCapabilities, and the available capabilities of the graphics device.
    * @param shareWith
    *           Optional GLContext to share state (textures, vbos, shaders, etc.) with.
    */
   public GLCanvas(final Composite parent, final int style, GLCapabilitiesImmutable caps,
                   final GLCapabilitiesChooser chooser, final GLContext shareWith) {
      /* NO_BACKGROUND required to avoid clearing bg in native SWT widget (we do this in the GL display) */
      super(parent, style | SWT.NO_BACKGROUND);

      GLProfile.initSingleton(); // ensure JOGL is completly initialized

      SWTAccessor.setRealized(this, true);

      clientArea = GLCanvas.this.getClientArea();

      /* Get the nativewindow-Graphics Device associated with this control (which is determined by the parent Composite) */
      device = SWTAccessor.getDevice(this);
      /* Native handle for the control, used to associate with GLContext */
      nativeWindowHandle = SWTAccessor.getWindowHandle(this);

      /* Select default GLCapabilities if none was provided, otherwise clone provided caps to ensure safety */
      if(null == caps) {
          caps = new GLCapabilities(GLProfile.getDefault(device));
      }
      glCapsRequested = caps;

      final GLDrawableFactory glFactory = GLDrawableFactory.getFactory(caps.getGLProfile());

      /* Create a NativeWindow proxy for the SWT canvas */
      proxySurface = glFactory.createProxySurface(device, nativeWindowHandle, caps, chooser);

      /* Associate a GL surface with the proxy */
      drawable = glFactory.createGLDrawable(proxySurface);
      drawable.setRealized(true);

      context = drawable.createContext(shareWith);

      /* Register SWT listeners (e.g. PaintListener) to render/resize GL surface. */
      /* TODO: verify that these do not need to be manually de-registered when destroying the SWT component */
      addPaintListener(new PaintListener() {
         @Override
        public void paintControl(final PaintEvent arg0) {
            if (!helper.isExternalAnimatorAnimating()) {
               display();
            }
         }
      });

      addControlListener(new ControlAdapter() {
         @Override
         public void controlResized(final ControlEvent arg0) {
            clientArea = GLCanvas.this.getClientArea();
            /* Mark for OpenGL reshape next time the control is painted */
            sendReshape = true;
         }
      });
   }

   @Override
   public void addGLEventListener(final GLEventListener arg0) {
      helper.addGLEventListener(arg0);
   }

   @Override
   public void addGLEventListener(final int arg0, final GLEventListener arg1) throws IndexOutOfBoundsException {
      helper.addGLEventListener(arg0, arg1);
   }

   /**
    * {@inheritDoc}
    *
    * <p>
    * This impl. calls this class's {@link #dispose()} SWT override,
    * where the actual implementation resides.
    * </p>
    */
   @Override
   public void destroy() {
      dispose();
   }

   @Override
   public void display() {
      runInGLThread(makeCurrentAndDisplayAction, displayAction);
   }

   @Override
   public GLAnimatorControl getAnimator() {
      return helper.getAnimator();
   }

   @Override
   public boolean getAutoSwapBufferMode() {
      return helper.getAutoSwapBufferMode();
   }

   @Override
   public GLContext getContext() {
      return context;
   }

   @Override
   public int getContextCreationFlags() {
      return additionalCtxCreationFlags;
   }

   @Override
   public GL getGL() {
      return (null == context) ? null : context.getGL();
   }

   @Override
   public boolean invoke(final boolean wait, final GLRunnable run) {
      return helper.invoke(this, wait, run);
   }

   @Override
   public void removeGLEventListener(final GLEventListener arg0) {
      helper.removeGLEventListener(arg0);
   }

   @Override
   public GLEventListener removeGLEventListener(int index) throws IndexOutOfBoundsException {
      return helper.removeGLEventListener(index);
   }
       
   @Override
   public void setAnimator(final GLAnimatorControl arg0) throws GLException {
      helper.setAnimator(arg0);
   }

   @Override
   public void setAutoSwapBufferMode(final boolean arg0) {
      helper.setAutoSwapBufferMode(arg0);
   }

   @Override
   public GLContext setContext(GLContext newCtx) {
      final GLContext oldCtx = context;
      final boolean newCtxCurrent = helper.switchContext(drawable, oldCtx, newCtx, additionalCtxCreationFlags);
      context=(GLContextImpl)newCtx;
      if(newCtxCurrent) {
          context.makeCurrent();
      }
      return oldCtx;
   }

   @Override
   public void setContextCreationFlags(final int arg0) {
      additionalCtxCreationFlags = arg0;
      if(null != context) {
        context.setContextCreationFlags(additionalCtxCreationFlags);
      }
   }

   @Override
   public GL setGL(final GL arg0) {
      if (null != context) {
         context.setGL(arg0);
         return arg0;
      }
      return null;
   }

   @Override
   public GLContext createContext(final GLContext shareWith) {
     if(drawable != null) {
         final GLContext _ctx = drawable.createContext(shareWith);
         _ctx.setContextCreationFlags(additionalCtxCreationFlags);
         return _ctx;
     }
     return null;
   }

   @Override
   public GLCapabilitiesImmutable getChosenGLCapabilities() {
      return (GLCapabilitiesImmutable)proxySurface.getGraphicsConfiguration().getChosenCapabilities();
   }

   /**
    * Accessor for the GLCapabilities that were requested (via the constructor parameter).
    *
    * @return Non-null GLCapabilities.
    */
   public GLCapabilitiesImmutable getRequestedGLCapabilities() {
      return (GLCapabilitiesImmutable)proxySurface.getGraphicsConfiguration().getRequestedCapabilities();
   }

   @Override
   public GLDrawableFactory getFactory() {
      return (drawable != null) ? drawable.getFactory() : null;
   }

   @Override
   public GLProfile getGLProfile() {
      return glCapsRequested.getGLProfile();
   }

   @Override
   public long getHandle() {
      return (drawable != null) ? drawable.getHandle() : 0;
   }

   @Override
   public int getHeight() {
      final Rectangle clientArea = this.clientArea;
      if (clientArea == null) return 0;
      return clientArea.height;
   }

   @Override
   public NativeSurface getNativeSurface() {
      return (drawable != null) ? drawable.getNativeSurface() : null;
   }

   @Override
   public int getWidth() {
      final Rectangle clientArea = this.clientArea;
      if (clientArea == null) return 0;
      return clientArea.width;
   }

   @Override
   public boolean isRealized() {
      return (drawable != null) ? drawable.isRealized() : false;
   }

   @Override
   public void setRealized(final boolean arg0) {
      /* Intentionally empty */
   }

   @Override
   public void swapBuffers() throws GLException {
      runInGLThread(makeCurrentAndSwapBuffersAction, swapBuffersAction);
   }

   // FIXME: API of update() method ?
   @Override
   public void update() {
    // FIXME:     display();
   }

   @Override
   public void dispose() {
     if (null != drawable && null != context) { // drawable is volatile!
        boolean animatorPaused = false;
        final GLAnimatorControl animator = getAnimator();
        if (null != animator) {
           // can't remove us from animator for recreational addNotify()
           animatorPaused = animator.pause();
        }

        if(context.isCreated()) {
            if (Threading.isSingleThreaded() && !Threading.isOpenGLThread()) {
               runInDesignatedGLThread(disposeOnEDTGLAction);
            } else if (context.isCreated()) {
               helper.disposeGL(GLCanvas.this, drawable, context, postDisposeGLAction);
            }
        }

        if (animatorPaused) {
           animator.resume();
        }
     }
     final Display display = getDisplay();

     if (display.getThread() == Thread.currentThread()) {
        disposeGraphicsDeviceAction.run();
     } else {
        display.syncExec(disposeGraphicsDeviceAction);
     }
     super.dispose();
   }

   /**
    * Determines whether the current thread is the appropriate thread to use the GLContext in. If we are using one of
    * the single-threaded policies in {@link Threading}, than this is either the SWT event dispatch thread, or the
    * OpenGL worker thread depending on the state of {@link #useSWTThread}. Otherwise this always returns true because
    * the threading model is user defined.
    * <p>
    * FIXME: Redundant .. remove! Merge isRenderThread, runInGLThread and runInDesignatedGLThread
    *
    * @return true if the calling thread is the correct thread to execute OpenGL calls in, false otherwise.
    */
   protected boolean isRenderThread() {
      if (Threading.isSingleThreaded()) {
         if (ThreadingImpl.getMode() != ThreadingImpl.Mode.ST_WORKER) {
            final Display display = getDisplay();
            return display != null && display.getThread() == Thread.currentThread();
         }
         return Threading.isOpenGLThread();
      }
      /*
       * For multi-threaded rendering, the render thread is not defined...
       */
      return true;
   }

   /**
    * Runs the specified action in the designated OpenGL thread. If the current thread is designated, then the
    * syncAction is run synchronously, otherwise the asyncAction is dispatched to the appropriate worker thread.
    *
    * @param asyncAction
    *           The non-null action to dispatch to an OpenGL worker thread. This action should not assume that a
    *           GLContext is current when invoked.
    * @param syncAction
    *           The non-null action to run synchronously if the current thread is designated to handle OpenGL calls.
    *           This action may assume the GLContext is current.
    * FIXME: Redundant .. remove! Merge isRenderThread, runInGLThread and runInDesignatedGLThread
    */
   private void runInGLThread(final Runnable asyncAction, final Runnable syncAction) {
      if (Threading.isSingleThreaded() && !isRenderThread()) {
         /* Run in designated GL thread */
         runInDesignatedGLThread(asyncAction);
      } else {
         /* Run in current thread... */
         helper.invokeGL(drawable, context, syncAction, initAction);
      }
   }

   /**
    * Dispatches the specified runnable to the appropriate OpenGL worker thread (either the SWT event dispatch thread,
    * or the OpenGL worker thread depending on the state of {@link #useSWTThread}).
    *
    * @param makeCurrentAndRunAction
    *           The non-null action to dispatch.
    * FIXME: Redundant .. remove! Merge isRenderThread, runInGLThread and runInDesignatedGLThread
    */
   private void runInDesignatedGLThread(final Runnable makeCurrentAndRunAction) {
      if (ThreadingImpl.getMode() != ThreadingImpl.Mode.ST_WORKER) {
         final Display display = getDisplay();
         assert display.getThread() != Thread.currentThread() : "Incorrect use of thread dispatching.";
         display.syncExec(makeCurrentAndRunAction);
      } else {
         Threading.invokeOnOpenGLThread(true, makeCurrentAndRunAction);
      }
   }


   public static void main(final String[] args) {
       System.err.println(VersionUtil.getPlatformInfo());
       System.err.println(GlueGenVersion.getInstance());
       // System.err.println(NativeWindowVersion.getInstance());
       System.err.println(JoglVersion.getInstance());

       System.err.println(JoglVersion.getDefaultOpenGLInfo(null, true).toString());

       final GLCapabilitiesImmutable caps = new GLCapabilities( GLProfile.getDefault(GLProfile.getDefaultDevice()) );
       final Display display = new Display();
       final Shell shell = new Shell(display);
       shell.setSize(128,128);
       shell.setLayout(new FillLayout());

       final GLCanvas canvas = new GLCanvas(shell, 0, caps, null, null);

       canvas.addGLEventListener(new GLEventListener() {
           @Override
           public void init(final GLAutoDrawable drawable) {
               GL gl = drawable.getGL();
               System.err.println(JoglVersion.getGLInfo(gl, null));
           }
           @Override
           public void reshape(final GLAutoDrawable drawable, final int x, final int y, final int width, final int height) {}
           @Override
           public void display(final GLAutoDrawable drawable) {}
           @Override
           public void dispose(final GLAutoDrawable drawable) {}
       });
       shell.open();
       canvas.display();
       display.dispose();
   }
}
