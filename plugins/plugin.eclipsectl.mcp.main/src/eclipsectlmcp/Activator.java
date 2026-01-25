package eclipsectlmcp;

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import eclipsectlmcp.preferences.mcp.McpHttpServerPreferencePresenter;
import eclipsectlmcp.preferences.mcp.McpServerPreferencePresenter;
import eclipsectlmcp.preferences.tmux.TmuxPreferencePresenter;

public class Activator extends AbstractUIPlugin 
{
    private static Activator plugin = null;
    
    @Override
    public void start(BundleContext context) throws Exception 
    {
        super.start(context);
        plugin = this;
    }
    
    public static Activator getDefault()
    {
        return plugin;
    }

    public McpHttpServerPreferencePresenter getHttpMcpServerPreferencePresenter()
    {
        return make( McpHttpServerPreferencePresenter.class );
    }

    public McpServerPreferencePresenter getMCPServerPreferencePresenter()
    {
        return make( McpServerPreferencePresenter.class );
    }

    public TmuxPreferencePresenter getTmuxPreferencePresenter()
    {
        return make( TmuxPreferencePresenter.class );
    }
    
    public <T> T make ( Class<T> clazz )
    {
        IEclipseContext eclipseContext;
        try
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            eclipseContext = workbench.getService( IEclipseContext.class );
        }
        catch ( Exception e )
        {
            BundleContext bundleContext = getBundle().getBundleContext();
            eclipseContext =  EclipseContextFactory.getServiceContext( bundleContext );
        }
        T instance = ContextInjectionFactory.make( clazz, eclipseContext );
        return instance;
    }
}
