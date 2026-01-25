package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.eclipse.jdt.internal.corext.callhierarchy.CallHierarchy;
import org.eclipse.jdt.internal.corext.callhierarchy.MethodWrapper;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

public class EclipseSearchService {
	private static ILog logger = Platform.getLog(EclipseSearchService.class);

	public static String openType(String typeName) {
	    try {
	        List<String> results = new ArrayList<>();
	        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
	        
	        // Reproduire exactement la logique du dialogue Open Type
	        String searchPattern = typeName;
	        if (!searchPattern.contains("*") && !searchPattern.contains("?")) {
	            searchPattern = "*" + searchPattern + "*"; // Recherche partielle comme Eclipse
	        }
	        
	        SearchEngine engine = new SearchEngine();
	        engine.searchAllTypeNames(
	            null, // tous les packages
	            SearchPattern.R_EXACT_MATCH,
	            searchPattern.toCharArray(),
	            SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE,
	            IJavaSearchConstants.TYPE,
	            scope,
				new TypeNameMatchRequestor() {

					@Override
					public void acceptTypeNameMatch(TypeNameMatch match) {
						String fullyQualifiedName = match.getFullyQualifiedName();
						String className = match.getSimpleTypeName();

						// Optionnel : chemin du fichier
						String filePath = "";
						try {
							IResource resource = match.getType().getResource();
							if (resource != null) {
								filePath = resource.getFullPath().toString();
							} else {
								IPackageFragmentRoot root = (IPackageFragmentRoot) match.getType()
										.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
								filePath = root.getPath().toString();
							}
						} catch (Exception e) {
							filePath = "N/A";
						}

						results.add(className + " - " + fullyQualifiedName + " - " + filePath);
					}

				},
	            IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
	            new NullProgressMonitor()
	        );
	        
	        if(results.isEmpty()) {
	        	return "No types found for: " + typeName;
	        }	        
	        return String.join("\n", results);	    
	    }
	    catch (Exception e) {
            logger.error(e.getMessage(), e);
	        return "Error in open type: " + e.getMessage();
	    }
	}

	/**
     * Retrieves the call hierarchy for a specified method.
     * 
     * @param fullyQualifiedClassName The fully qualified name of the class containing the method
     * @param methodName The name of the method to analyze
     * @param methodSignature The signature of the method (optional)
     * @param maxDepth Maximum depth of the call hierarchy to retrieve
     * @return A formatted string containing the call hierarchy
     */
    public static String callHierarchy(String fullyQualifiedClassName, 
                                  String methodName, 
                                  String methodSignature)
    {
        int maxDepth = 1;
        try {
            IMethod targetMethod = findTargetMethod(fullyQualifiedClassName, methodName, methodSignature);
            if (targetMethod == null) {
                return "Method not found: " + fullyQualifiedClassName + "." + methodName;
            }
            
            List<String> results = new ArrayList<>();
            
            results.add(formatMethodInfo(targetMethod, 0));
            
            CallHierarchy callHierarchy = CallHierarchy.getDefault();
            
            results.add("Caller Hierarchy:");
            MethodWrapper[] callerRoots = callHierarchy.getCallerRoots(new IMethod[] {targetMethod});
            collectCallers(callerRoots, 0, maxDepth, results);
            
            results.add("Callee Hierarchy:");
            MethodWrapper[] calleeRoots = callHierarchy.getCalleeRoots(new IMethod[] {targetMethod});
            collectCallees(calleeRoots, 0, maxDepth, results);
            
            return results.isEmpty() ? "No references found" : String.join("\n", results);
            
        } catch (Exception e) {
        	e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }
    
	private static IJavaProject[] getAvailableJavaProjects() {
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IProject[] projects = root.getProjects();
            
            List<IJavaProject> javaProjects = new ArrayList<>();
            for (IProject project : projects) {
                if (project.isOpen() && project.hasNature(JavaCore.NATURE_ID)) {
                    javaProjects.add(JavaCore.create(project));
                }
            }
            
            return javaProjects.toArray(new IJavaProject[0]);
        } catch (Exception e) {
            return new IJavaProject[0];
        }
    }
    private static IMethod findTargetMethod(String fullyQualifiedClassName, 
            String methodName, 
            String methodSignature) throws JavaModelException {

		for (IJavaProject project : getAvailableJavaProjects()) {
			IType type = project.findType(fullyQualifiedClassName);
			if (type == null) {
				continue;
			}

			// Si une signature de m�thode est fournie, l'utiliser pour trouver la m�thode exacte
			if (methodSignature != null && !methodSignature.isEmpty()) {
				IMethod targetMethod = type.getMethod(methodName, methodSignature.split(","));
				if (targetMethod != null && targetMethod.exists()) {
					return targetMethod;
				}
			} else {
				// Essayer de trouver la m�thode sans signature
				IMethod[] methods = type.getMethods();
				for (IMethod method : methods) {
					if (method.getElementName().equals(methodName)) {
						return method;
					}
				}
			}
		}

		return null;
	}

	private static void collectCallers(MethodWrapper[] wrappers, int currentDepth, int maxDepth, List<String> results) {
        if (wrappers == null) return;
        
		for (MethodWrapper wrapper : wrappers) {
			if (wrapper.getMember() instanceof IMethod method) {
				if (currentDepth > 0) {
					String info = formatMethodInfo(method, currentDepth);
					results.add(info);
				}
				if (currentDepth < maxDepth) {
					// R�cursion pour les niveaux suivants
					MethodWrapper[] callers = wrapper.getCalls(new NullProgressMonitor());
					collectCallers(callers, currentDepth + 1, maxDepth, results);
				}
			}
		}
	}

    private static void collectCallees(MethodWrapper[] wrappers, int currentDepth, int maxDepth, List<String> results) {
        if (wrappers == null) return;
        
        for (MethodWrapper wrapper : wrappers) {
            if (wrapper.getMember() instanceof IMethod method){
            	if (currentDepth > 0) {
					String info = formatMethodInfo(method, currentDepth);
					results.add(info);
				}
            	if (currentDepth < maxDepth) {
	                // R�cursion pour les niveaux suivants
	                MethodWrapper[] callees = wrapper.getCalls(new NullProgressMonitor());
	                collectCallees(callees, currentDepth + 1, maxDepth, results);
            	}
            }
        }
    }

    private static String formatMethodInfo(IMethod method, int depth) {
        try {
            String indent = "  ".repeat(depth);
            String className = method.getDeclaringType().getFullyQualifiedName();
            String methodName = method.getElementName();
            String signature = getMethodSignature(method);
            String returnType;
            try {
            	returnType = Signature.toString(method.getReturnType());
    		} catch (JavaModelException e) {
    			returnType = "";
    		}
            
            int startLine = -1;
            int endLine = -1;
            IPath filepath=null;
            
            ICompilationUnit compilationUnit = method.getCompilationUnit();
            if (compilationUnit != null) {
                try {
    	            ISourceRange sourceRange = method.getSourceRange();
    	            IDocument document = new Document(compilationUnit.getSource());
                    startLine = document.getLineOfOffset(sourceRange.getOffset()) + 1;
                    endLine = document.getLineOfOffset(sourceRange.getOffset() + sourceRange.getLength()) + 1;
                    
                    if(compilationUnit.getResource() instanceof IFile file) {
                    	filepath = file.getProjectRelativePath();
                    }
                    
                } catch (BadLocationException | JavaModelException e) {
                    e.printStackTrace();
                }
            }
            
            String info =  indent + className + "." + methodName + signature+" : "+returnType;
            if(filepath!=null) {
            	info+=" "+filepath+":"+startLine+"-"+endLine;
            }
            return info;
        } 
        catch (Exception e) {
            return "ERROR_FORMATTING_METHOD";
        }
    }
    
    private static String getMethodSignature(IMethod method) {
        try {
            String[] paramTypes = method.getParameterTypes();
            StringBuilder sig = new StringBuilder("(");
            
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) sig.append(",");
                
                // Convertir signature JDT en nom lisible
                String paramType = Signature.toString(paramTypes[i]);
                sig.append(paramType);
            }
            
            sig.append(")");
            return sig.toString();
        } catch (Exception e) {
            return "()";
        }
    }

    /**
     * Finds all references (usages) of a Java element across the workspace.
     *
     * @param elementType The type of element: "type", "method", "field", "package", "constructor"
     * @param elementName The name of the element (fully qualified for types, simple or qualified for methods/fields)
     * @param projectName Optional project name to limit search scope
     * @param scope Search scope: "workspace" (default) or "project"
     * @param includeSubtypes For type searches, include references to subtypes
     * @return Formatted string containing all references with line numbers and context
     */
    public static String findReferences(String elementType, String elementName,
                                         String projectName, String scope,
                                         boolean includeSubtypes) {
        try {
            // Create search pattern
            int searchFor = parseElementType(elementType);
            int limitTo = org.eclipse.jdt.core.search.IJavaSearchConstants.REFERENCES;

            org.eclipse.jdt.core.search.SearchPattern pattern = org.eclipse.jdt.core.search.SearchPattern.createPattern(
                elementName,
                searchFor,
                limitTo,
                org.eclipse.jdt.core.search.SearchPattern.R_EXACT_MATCH
            );

            if (pattern == null) {
                return "Error: Could not create search pattern for element: " + elementName;
            }

            // Create search scope
            org.eclipse.jdt.core.search.IJavaSearchScope searchScope = createSearchScope(projectName, scope);

            // Collect results
            List<ReferenceMatch> matches = new ArrayList<>();
            org.eclipse.jdt.core.search.SearchRequestor requestor = new org.eclipse.jdt.core.search.SearchRequestor() {
                @Override
                public void acceptSearchMatch(org.eclipse.jdt.core.search.SearchMatch match) {
                    try {
                        ReferenceMatch ref = createReferenceMatch(match);
                        if (ref != null) {
                            matches.add(ref);
                        }
                    } catch (Exception e) {
                        logger.error("Error processing search match: " + e.getMessage(), e);
                    }
                }
            };

            // Execute search
            org.eclipse.jdt.core.search.SearchEngine engine = new org.eclipse.jdt.core.search.SearchEngine();
            engine.search(
                pattern,
                new org.eclipse.jdt.core.search.SearchParticipant[] {
                    org.eclipse.jdt.core.search.SearchEngine.getDefaultSearchParticipant()
                },
                searchScope,
                requestor,
                new NullProgressMonitor()
            );

            // Format results
            return formatReferencesOutput(elementType, elementName, matches);

        } catch (Exception e) {
            logger.error("Error finding references", e);
            return "Error finding references: " + e.getMessage();
        }
    }

    /**
     * Parses element type string to Eclipse search constant.
     */
    private static int parseElementType(String elementType) {
        return switch (elementType.toLowerCase()) {
            case "type", "class", "interface", "enum" ->
                org.eclipse.jdt.core.search.IJavaSearchConstants.TYPE;
            case "method" ->
                org.eclipse.jdt.core.search.IJavaSearchConstants.METHOD;
            case "field" ->
                org.eclipse.jdt.core.search.IJavaSearchConstants.FIELD;
            case "package" ->
                org.eclipse.jdt.core.search.IJavaSearchConstants.PACKAGE;
            case "constructor" ->
                org.eclipse.jdt.core.search.IJavaSearchConstants.CONSTRUCTOR;
            default ->
                org.eclipse.jdt.core.search.IJavaSearchConstants.TYPE;
        };
    }

    /**
     * Creates search scope based on parameters.
     */
    private static org.eclipse.jdt.core.search.IJavaSearchScope createSearchScope(String projectName, String scope) {
        if ("project".equalsIgnoreCase(scope) && projectName != null && !projectName.isEmpty()) {
            IJavaProject[] projects = getAvailableJavaProjects();
            for (IJavaProject project : projects) {
                if (project.getElementName().equals(projectName)) {
                    return org.eclipse.jdt.core.search.SearchEngine.createJavaSearchScope(
                        new IJavaElement[] { project }
                    );
                }
            }
        }

        // Default to workspace scope
        return org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope();
    }

    /**
     * Creates a reference match object from a search match.
     */
    private static ReferenceMatch createReferenceMatch(org.eclipse.jdt.core.search.SearchMatch match) {
        try {
            ReferenceMatch ref = new ReferenceMatch();
            ref.resource = match.getResource().getFullPath().toString();
            ref.offset = match.getOffset();
            ref.length = match.getLength();

            // Get line number and context
            if (match.getResource() instanceof IFile file) {
                try {
                    ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
                    if (cu != null && cu.exists()) {
                        IDocument doc = new Document(cu.getSource());
                        ref.lineNumber = doc.getLineOfOffset(match.getOffset()) + 1;

                        // Extract line context
                        int lineStart = doc.getLineOffset(ref.lineNumber - 1);
                        int lineEnd = lineStart + doc.getLineLength(ref.lineNumber - 1);
                        ref.context = doc.get(lineStart, lineEnd - lineStart).trim();
                    }
                } catch (Exception e) {
                    // Ignore if context extraction fails
                }
            }

            return ref;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Formats references output as a readable string.
     */
    private static String formatReferencesOutput(String elementType, String elementName, List<ReferenceMatch> matches) {
        if (matches.isEmpty()) {
            return "No references found for " + elementType + ": " + elementName;
        }

        StringBuilder result = new StringBuilder();
        result.append("References Found: ").append(matches.size()).append("\n\n");
        result.append("Element: ").append(elementName).append("\n");
        result.append("Type: ").append(elementType).append("\n");
        result.append("Scope: Workspace\n\n");
        result.append("References by file:\n\n");

        // Group by file
        java.util.Map<String, List<ReferenceMatch>> byFile = new java.util.HashMap<>();
        for (ReferenceMatch match : matches) {
            byFile.computeIfAbsent(match.resource, k -> new ArrayList<>()).add(match);
        }

        // Sort files alphabetically
        List<String> sortedFiles = new ArrayList<>(byFile.keySet());
        sortedFiles.sort(String::compareTo);

        for (String file : sortedFiles) {
            List<ReferenceMatch> fileMatches = byFile.get(file);

            result.append(file).append(" (").append(fileMatches.size()).append(" reference");
            if (fileMatches.size() > 1) result.append("s");
            result.append("):\n");

            for (ReferenceMatch match : fileMatches) {
                result.append("  Line ").append(match.lineNumber).append(": ");
                result.append(match.context != null ? match.context : "(context unavailable)");
                result.append("\n");
            }

            result.append("\n");
        }

        return result.toString();
    }

    /**
     * Retrieves the type hierarchy (superclasses, subclasses, interfaces) for a Java type.
     */
    public static String getTypeHierarchy(String fullyQualifiedClassName) {
        try {
            IType type = null;
            for (IJavaProject project : getAvailableJavaProjects()) {
                type = project.findType(fullyQualifiedClassName);
                if (type != null) break;
            }
            if (type == null) {
                return "Type not found: " + fullyQualifiedClassName;
            }

            ITypeHierarchy hierarchy = type.newTypeHierarchy(new NullProgressMonitor());
            List<String> results = new ArrayList<>();

            results.add("Type Hierarchy for: " + fullyQualifiedClassName);
            results.add("");

            // Superclasses
            IType[] superclasses = hierarchy.getAllSuperclasses(type);
            results.add("Superclasses:");
            if (superclasses.length == 0) {
                results.add("  (none)");
            } else {
                for (IType sup : superclasses) {
                    results.add("  " + sup.getFullyQualifiedName() + formatTypePath(sup));
                }
            }

            // Interfaces
            IType[] interfaces = hierarchy.getAllSuperInterfaces(type);
            results.add("");
            results.add("Implemented/Extended Interfaces:");
            if (interfaces.length == 0) {
                results.add("  (none)");
            } else {
                for (IType iface : interfaces) {
                    results.add("  " + iface.getFullyQualifiedName() + formatTypePath(iface));
                }
            }

            // Subclasses
            IType[] subtypes = hierarchy.getAllSubtypes(type);
            results.add("");
            results.add("Subtypes:");
            if (subtypes.length == 0) {
                results.add("  (none)");
            } else {
                for (IType sub : subtypes) {
                    results.add("  " + sub.getFullyQualifiedName() + formatTypePath(sub));
                }
            }

            return String.join("\n", results);
        } catch (Exception e) {
            logger.error("Error getting type hierarchy", e);
            return "Error getting type hierarchy: " + e.getMessage();
        }
    }

    private static String formatTypePath(IType type) {
        try {
            IResource resource = type.getResource();
            if (resource != null) {
                return " (" + resource.getFullPath().toString() + ")";
            }
            IPackageFragmentRoot root = (IPackageFragmentRoot) type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
            if (root != null) {
                return " (" + root.getPath().toString() + ")";
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    /**
     * Finds all implementations of an interface or abstract class, optionally filtering by method name.
     */
    public static String getImplementations(String fullyQualifiedClassName, String methodName) {
        try {
            IType type = null;
            for (IJavaProject project : getAvailableJavaProjects()) {
                type = project.findType(fullyQualifiedClassName);
                if (type != null) break;
            }
            if (type == null) {
                return "Type not found: " + fullyQualifiedClassName;
            }

            // Find all implementing types using type hierarchy
            ITypeHierarchy hierarchy = type.newTypeHierarchy(new NullProgressMonitor());
            IType[] subtypes = hierarchy.getAllSubtypes(type);

            if (subtypes.length == 0) {
                return "No implementations found for: " + fullyQualifiedClassName;
            }

            List<String> results = new ArrayList<>();
            boolean filterByMethod = methodName != null && !methodName.isEmpty();

            if (filterByMethod) {
                results.add("Implementations of " + fullyQualifiedClassName + "." + methodName + ":");
            } else {
                results.add("Implementations of " + fullyQualifiedClassName + ":");
            }
            results.add("");

            for (IType subtype : subtypes) {
                if (filterByMethod) {
                    // Find matching methods in this subtype
                    for (IMethod method : subtype.getMethods()) {
                        if (method.getElementName().equals(methodName)) {
                            String info = formatMethodInfo(method, 0);
                            results.add("  " + info);
                        }
                    }
                } else {
                    results.add("  " + subtype.getFullyQualifiedName() + formatTypePath(subtype));
                }
            }

            if (results.size() <= 2) {
                return filterByMethod
                    ? "No implementations of method '" + methodName + "' found in subtypes of " + fullyQualifiedClassName
                    : "No implementations found for: " + fullyQualifiedClassName;
            }

            return String.join("\n", results);
        } catch (Exception e) {
            logger.error("Error finding implementations", e);
            return "Error finding implementations: " + e.getMessage();
        }
    }

    /**
     * Internal class to represent a reference match.
     */
    private static class ReferenceMatch {
        String resource;
        int offset;
        int length;
        int lineNumber;
        String context;
    }
}
