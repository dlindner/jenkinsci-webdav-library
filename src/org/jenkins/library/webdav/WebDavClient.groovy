package org.jenkins.library.webdav;

import java.io.File;
import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import org.jenkinsci.remoting.RoleChecker;

@Grab(group='com.github.lookfirst', module='sardine', version='5.13')
import com.github.sardine.*;
import com.github.sardine.impl.SardineException;
import org.apache.http.client.*;


class WebDavClient {
    /**
     * Jenkins FilePath representing the 'local' directory. All local file
     * patterns are resolved within this directory
     */
    protected FilePath local;

    /**
     * Full path to 'remote' directory, including the protocol and hostname.
     * All remote paths are resolved relative to this directory.
     */
    protected String remote;

    protected String user;
    protected String pass;

    def steps;

    WebDavClient(steps, local, remote, user, pass) {
        this.local = local;
        this.remote = remote;
        this.user = user;
        this.pass = pass;
        this.steps = steps
    }
    
    def onSardineConnection(operation) {
		def connection = SardineFactory.begin()
		connection.setCredentials(user, pass)
		operation(connection)
		connection.shutdown()
	}

    def mkdir(path) {
		onSardineConnection({ conn ->
	        /*
	         * WebDAV's MKCOL request cannot make directories recursively,
	         * so we have to do it manually in a loop.
	         */
	        def current = null
	        for (each in path.tokenize("/")) {
	            current = current == null ? each : current + "/" + each
	            try {
					def url = path2url(current, true)
	                conn.createDirectory(url)
	            } catch (HttpResponseException hte) {
	                /*
	                 * Apache's mod_dav (at least) respond with '301 Moved permamently'
	                 * when the directory already exist. Do nothing then.
	                 *
	                 * NGINX's ngx_http_dav_module responds with `405 Not Allowed`.
	                 */
	                def status = hte.getStatusCode()
	                if (status != 301 && status != 405) throw hte;
	            }
	        }
		})
    }

    def rm(path) {
		onSardineConnection({ conn ->
	        try {
	            if (isdir(path)) {
	                conn.delete(path2url(path + DavResource.SEPARATOR))
	            } else {
	                conn.delete(path2url(path))
	            }
	        } catch (HttpResponseException hte) {
	            /*
	             * Apache's mod_dav (at least) requires directories to be
	             * referred with trailing slash and signals this with
	             * '301 Moved permamently'. In that case, retry with
	             * trailing slash.
	             */
	            if (hte.getStatusCode() == 301) {
	                rm(path + DavResource.SEPARATOR)
	            } else {
	                throw hte
	            }
	        }
		})
    }

    def put(path, pattern) {
		onSardineConnection({ conn ->
	        for (file in this.local.list(pattern)) {
	            def dest = path2url(path + DavResource.SEPARATOR + file.getName());
	            def temp = File.createTempFile("put", null);
	            try {
	                file.copyTo(new FilePath(temp));
	                this.steps.echo "Uploading ${file.getName()}..."
	                conn.put(dest, temp, (String)null);
	                this.steps.echo "Uploaded ${file.getName()}"
	            } finally {
	                temp.delete()
	            }
	        }
        })
    }


    def ls(path = null) {
		onSardineConnection({ conn ->
	        def resources = conn.list(path2url(path))
	        resources.remove(0)
	        return resources.collect { it.getName().toString() }
        })
    }

    def isdir(path = null) {
		onSardineConnection({ conn ->
	        try {
	            def resources = conn.list(path2url(path))
	            return resources.size >= 0
	        } catch (SardineException se) {
	            return false;
	        }
        })
    }

    private def path2url(path, isdir = false) {
        def url = null
        if (path == null || path.equals('')) {
            url = remote
        } else {
            url = remote + DavResource.SEPARATOR + path
        }
        if (isdir && url.charAt(url.length()-1) != DavResource.SEPARATOR.charAt(0)) {
            url = url + DavResource.SEPARATOR
        }
        return url.replace(' ', '%20')
    }
}
