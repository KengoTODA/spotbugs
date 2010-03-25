/*
 * FindBugs - Find Bugs in Java programs
 * Copyright (C) 2003-2008 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.cloud;

import edu.umd.cs.findbugs.AppVersion;
import edu.umd.cs.findbugs.BugCollection;
import edu.umd.cs.findbugs.BugDesignation;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.I18N;
import edu.umd.cs.findbugs.PackageStats;
import edu.umd.cs.findbugs.ProjectStats;
import edu.umd.cs.findbugs.PropertyBundle;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.cloud.username.NameLookup;
import edu.umd.cs.findbugs.util.ClassName;
import edu.umd.cs.findbugs.util.Multiset;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;


/**
 * @author pwilliam
 */
public abstract class AbstractCloud implements Cloud {
	
    protected static final boolean THROW_EXCEPTION_IF_CANT_CONNECT = false;
    private static final Mode DEFAULT_VOTING_MODE = Mode.COMMUNAL;
    private static final Logger LOGGER = Logger.getLogger(AbstractCloud.class.getName());

    private static final String LEADERBOARD_BLACKLIST = SystemProperties.getProperty("findbugs.leaderboard.blacklist");
    private static final Pattern LEADERBOARD_BLACKLIST_PATTERN;
 	static {
 		Pattern p = null;
 		if (LEADERBOARD_BLACKLIST != null) {
 			try {
 				p = Pattern.compile(LEADERBOARD_BLACKLIST.replace(',', '|'));
 			} catch (Exception e) {
 				LOGGER.log(Level.WARNING, "Could not load leaderboard blacklist pattern", e);
 			}
         }
         LEADERBOARD_BLACKLIST_PATTERN = p;
 	}

 	protected final CloudPlugin plugin;
	protected final BugCollection bugCollection;
	protected final PropertyBundle properties;
	@CheckForNull
    private Pattern sourceFileLinkPattern;
	private String sourceFileLinkFormat;
	private String sourceFileLinkFormatWithLine;

	private String sourceFileLinkToolTip;

	private CopyOnWriteArraySet<CloudListener> listeners = new CopyOnWriteArraySet<CloudListener>();
	
	private Mode mode = Mode.COMMUNAL;
	private String statusMsg;

    protected AbstractCloud(CloudPlugin plugin, BugCollection bugs) {
		this.plugin = plugin;
		this.bugCollection = bugs;
		this.properties = plugin.getProperties();
	}

    public boolean initialize() throws IOException {
        String modeString = getCloudProperty("votingmode");
        Mode newMode = DEFAULT_VOTING_MODE;
        if (modeString != null) {
            try {
                newMode = Mode.valueOf(modeString.toUpperCase());
            } catch (IllegalArgumentException e) {
                LOGGER.log(Level.WARNING, "No such voting mode " + modeString, e);
            }
        }
        setMode(newMode);

        String sp = properties.getProperty("findbugs.sourcelink.pattern");
        String sf = properties.getProperty("findbugs.sourcelink.format");
        String sfwl = properties.getProperty("findbugs.sourcelink.formatWithLine");

        String stt  = properties.getProperty("findbugs.sourcelink.tooltip");
        if (sp != null && sf != null) {
            try {
            this.sourceFileLinkPattern = Pattern.compile(sp);
            this.sourceFileLinkFormat = sf;
            this.sourceFileLinkToolTip = stt;
            this.sourceFileLinkFormatWithLine = sfwl;
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not compile pattern " + sp, e);
                if (THROW_EXCEPTION_IF_CANT_CONNECT)
                    throw e;
            }
        }
        return true;
    }

    public Mode getMode() {
		return mode;
	}
	public void setMode(Mode mode) {
		this.mode = mode;
	}
	
	public CloudPlugin getPlugin() {
	    return plugin;
    }
	
	public BugCollection getBugCollection() {
		return bugCollection;
	}
	
	public boolean supportsBugLinks() {
		return false;
	}

	public boolean supportsClaims() {
		return false;
	}

	public boolean supportsCloudReports() {
		return true;
	}

	public String claimedBy(BugInstance b) {
		throw new UnsupportedOperationException();
	}
	
	public boolean claim(BugInstance b) {
		throw new UnsupportedOperationException();
	}

	public URL getBugLink(BugInstance b) {
		throw new UnsupportedOperationException();
	}
	public BugFilingStatus getBugLinkStatus(BugInstance b) {
		throw new UnsupportedOperationException();
	}

    public boolean canSeeCommentsByOthers(BugInstance bug) {
       switch(getMode()) {
       case SECRET: return false;
       case COMMUNAL : return true;
       case VOTING : return hasVoted(bug);
       }
       throw new IllegalStateException();
    }
    
    public boolean hasVoted(BugInstance bug) {
    	for(BugDesignation bd : getAllUserDesignations(bug))
    		if (getUser().equals(bd.getUser())) 
    			return true;
    	return false;
    }
    
    public String getCloudReport(BugInstance b) {
		SimpleDateFormat format = new SimpleDateFormat("MM/dd, yyyy");
		StringBuilder builder = new StringBuilder();
		long firstSeen = getFirstSeen(b);
		builder.append(String.format("First seen %s%n", format.format(new Date(firstSeen))));
        builder.append("\n");
		
		
		I18N i18n = I18N.instance();
		boolean canSeeCommentsByOthers = canSeeCommentsByOthers(b);
		if (canSeeCommentsByOthers && supportsBugLinks()) {
			BugFilingStatus bugLinkStatus = getBugLinkStatus(b);
			if (bugLinkStatus != null && bugLinkStatus.bugIsFiled()) {
				//if (bugLinkStatus.)
                builder.append("\nBug status is ").append(getBugStatus(b));
				//else
				//	builder.append("\nBug assigned to " + bd.bugAssignedTo + ", status is " + bd.bugStatus);
				
				builder.append("\n\n");
			}
		}
        String me = getUser();
        for(BugDesignation d : getAllUserDesignations(b)) {
            if ((me != null && me.equals(d.getUser()))|| canSeeCommentsByOthers ) {
                builder.append(String.format("%s @ %s: %s%n", d.getUser(), format.format(new Date(d.getTimestamp())),
                        i18n.getUserDesignation(d.getDesignationKey())));
                String annotationText = d.getAnnotationText();
                if (annotationText != null && annotationText.length() > 0) {
                    builder.append(annotationText);
                    builder.append("\n\n");
                }
            }
        }
		return builder.toString();
	}

    protected String getBugStatus(BugInstance b) {
        return null;
    }

    public Date getUserDate(BugInstance b) {
		return new Date(getUserTimestamp(b));
	}

	public void addListener(CloudListener listener) {
		if (listener == null) throw new NullPointerException();
		if (!listeners.contains(listener))
			listeners.add(listener);
	}

	public void removeListener(CloudListener listener) {
		listeners.remove(listener);
	}
	
    public String getStatusMsg() {
	   return statusMsg;
    }

    public void shutdown() {
	    
    }

    public boolean getIWillFix(BugInstance b) {
    	return getUserDesignation(b) == UserDesignation.I_WILL_FIX;
    }
    
    public boolean overallClassificationIsNotAProblem(BugInstance b) {
		return false;
	}
    
	public  double getClassificationScore(BugInstance b) {
		return getUserDesignation(b).score();
	}
	public  double getPortionObsoleteClassifications(BugInstance b) {
		if ( getUserDesignation(b) == UserDesignation.OBSOLETE_CODE)
			return 1.0;
		return 0.0;
	}
	public  double getClassificationVariance(BugInstance b) {
		return 0;
	}
	public int getNumberReviewers(BugInstance b) {
		if (getUserDesignation(b) == UserDesignation.UNCLASSIFIED)
			return 0;
		return 1;
	  }
	
    @SuppressWarnings("boxing")
    public void printCloudSummary(PrintWriter w, Iterable<BugInstance> bugs, String[] packagePrefixes) {

    	Multiset<String> evaluations = new Multiset<String>();
    	Multiset<String> designations = new Multiset<String>();
    	Multiset<String> bugStatus = new Multiset<String>();
    	
    	int issuesWithThisManyReviews [] = new int[100];
    	I18N i18n = I18N.instance();
		Set<String> hashCodes = new HashSet<String>();
		for(BugInstance b : bugs) {
			hashCodes.add(b.getInstanceHash());
		}
		
		int packageCount = 0;
		int classCount = 0;
		int ncss = 0;
		ProjectStats projectStats = bugCollection.getProjectStats();
		for(PackageStats ps : projectStats.getPackageStats()) {
            int num = ps.getNumClasses();
            if (ClassName.matchedPrefixes(packagePrefixes, ps.getPackageName()) && num > 0) {
                packageCount++;
                ncss += ps.size();
                classCount += num;
            }
        }
		
		
		if (classCount == 0) {
			w.println("No classes were analyzed");
			return;
    	} 
		if (packagePrefixes != null && packagePrefixes.length > 0) {
			String lst = Arrays.asList(packagePrefixes).toString();
			w.println("Code analyzed in " + lst.substring(1, lst.length()-1));
		} else {
			w.println("Code analyzed");
		}
		w.printf("%,7d packages%n%,7d classes%n%,7d thousands of lines of non-commenting source statements%n",
				packageCount, classCount, (ncss+999)/1000);
		w.println();
		int count = 0;
		int notInCloud = 0;
		for(String hash : hashCodes) {
			BugInstance bd = getBugByHash(hash);
			if (bd == null) { 
				notInCloud++;
				continue;
			}
			count++;
    		HashSet<String> reviewers = new HashSet<String>();
    		BugFilingStatus linkStatus = supportsBugLinks() ? getBugLinkStatus(bd) : null;
			if (linkStatus != null)
    			bugStatus.add(linkStatus.name());
    		for(BugDesignation d : getAllUserDesignations(bd)) 
    		    if (reviewers.add(d.getUser())) {
    		    	evaluations.add(d.getUser());
    		    	designations.add(i18n.getUserDesignation(d.getDesignationKey()));
    		    }
    		
    		int numReviews = Math.min( reviewers.size(), issuesWithThisManyReviews.length -1);
    		issuesWithThisManyReviews[numReviews]++;
    		
    	}
		if (count == 0) {
			w.printf("None of the %d issues in the current view are in the cloud%n%n", notInCloud);
	    	return;
		}
		if (notInCloud == 0) {
			w.printf("Summary for %d issues that are in the current view%n%n", count);
		} else {
			w.printf("Summary for %d issues that are in the current view and cloud (%d not in cloud)%n%n", count, notInCloud);
		}
		if (evaluations.numKeys() == 0) {
			w.println("No evaluations found");
		} else {
	    	w.println("People who have performed the most reviews");
	    	printLeaderBoard(w, evaluations, 9, getUser(), true, "reviewer");
	    	w.println();
	    	w.println("Distribution of evaluations");
	    	printLeaderBoard(w, designations, 100, " --- ", false, "designation");
		}
    	
		if (bugStatus.numKeys() == 0) {
			w.println();
			w.println("No bugs filed");	
		} else {
			w.println();
	    	w.println("Distribution of bug status");
	    	printLeaderBoard(w, bugStatus, 100, " --- ", false, "status of filed bug");
		}
    	w.println();
    	w.println("Distribution of number of reviews");
    	for(int i = 0; i < issuesWithThisManyReviews.length; i++) 
    		if (issuesWithThisManyReviews[i] > 0) {
    		w.printf("%4d  with %3d review", issuesWithThisManyReviews[i], i);
    		if (i != 1) w.print("s");
    		w.println();
    			
    	}	
    }

    @SuppressWarnings("boxing")
    public static void printLeaderBoard2(PrintWriter w, Multiset<String> evaluations, int maxRows, String alwaysPrint,
             String format, String title) {
 	    int row = 1;
     	int position = 0;
     	int previousScore = -1;
     	boolean foundAlwaysPrint = false;
     		
     	for(Map.Entry<String,Integer> e : evaluations.entriesInDecreasingFrequency()) {
     		int num = e.getValue();
     		if (num != previousScore) {
     			position = row;
     			previousScore = num;
     		}
     		String key = e.getKey();
     		if (LEADERBOARD_BLACKLIST_PATTERN != null && LEADERBOARD_BLACKLIST_PATTERN.matcher(key).matches())
     			continue;
     		
     		boolean shouldAlwaysPrint = key.equals(alwaysPrint);
 			if (row <= maxRows || shouldAlwaysPrint) 
 				w.printf(format, position, num, key);
 			
 			if (shouldAlwaysPrint)
 				foundAlwaysPrint = true;
     		row++;
     		if (row >= maxRows) {
     			if (alwaysPrint == null) 
     				break;
     			if (foundAlwaysPrint) {
         			w.printf("Total of %d %ss%n", evaluations.numKeys(), title);
         			break;
         		} 
     		}
     		
     	}
     }
    
    public boolean supportsCloudSummaries() {
    	return true;
    }
    
    public boolean canStoreUserAnnotation(BugInstance bugInstance) {
    	return true;
    }

    public double getClassificationDisagreement(BugInstance b) {
	    return 0;
    }

    public UserDesignation getUserDesignation(BugInstance b) {
    	BugDesignation bd = getPrimaryDesignation(b);
    	if (bd == null) 
    		return UserDesignation.UNCLASSIFIED;
    	return UserDesignation.valueOf(bd.getDesignationKey());
    }

    public String getUserEvaluation(BugInstance b) {
    	BugDesignation bd = getPrimaryDesignation(b);
    	if (bd == null) return "";
    	String result =  bd.getAnnotationText();
    	if (result == null)
    		return "";
    	return result;
    }

    public long getUserTimestamp(BugInstance b) {
    	BugDesignation bd = getPrimaryDesignation(b);
    	if (bd == null) return Long.MAX_VALUE;
    	return bd.getTimestamp();
    	
    }

    public long getFirstSeen(BugInstance b) {
        long firstVersion = b.getFirstVersion();
        AppVersion v = getBugCollection().getAppVersionFromSequenceNumber(firstVersion);
        if (v == null)
            return getBugCollection().getTimestamp();
        return v.getTimestamp();
    }
    
    // ==================== end of public methods ==================

	protected void updatedStatus() {
		for (CloudListener listener : listeners)
			listener.statusUpdated();
	}

	public void updatedIssue(BugInstance bug) {
		for (CloudListener listener : listeners)
			listener.issueUpdated(bug);
	}

    protected abstract Iterable<BugDesignation> getAllUserDesignations(BugInstance bd);

	public BugInstance getBugByHash(String hash) {
		for (BugInstance instance : bugCollection.getCollection()) {
			if (instance.getInstanceHash().equals(hash)) {
				return instance;
			}
		}
		return null;
	}
    
    protected NameLookup getUsernameLookup() throws IOException {
    	NameLookup lookup;
        try {
	        lookup = plugin.getUsernameClass().newInstance();
        } catch (Exception e) {
        	throw new RuntimeException("Unable to obtain username", e);
        }
     	if (!lookup.initialize(plugin, bugCollection)) {
     		throw new RuntimeException("Unable to obtain username");
     	}
    	return lookup;
		
    }
    
	public void setStatusMsg(String newMsg) {
		this.statusMsg = newMsg;
		updatedStatus();
	}
    
    private static void printLeaderBoard(PrintWriter w, Multiset<String> evaluations, int maxRows, String alwaysPrint, boolean listRank, String title) {
    	 if (listRank)
 			w.printf("%3s %4s %s%n", "rnk", "num", title);
 		else
 			w.printf("%4s %s%n",  "num", title);
    	printLeaderBoard2(w, evaluations, maxRows, alwaysPrint, listRank ? "%3d %4d %s%n" : "%2$4d %3$s%n"  , title);
    }

    protected String getCloudProperty(String propertyName) {
        return properties.getProperty("findbugs.cloud." + propertyName);
    }

    public boolean supportsSourceLinks() {
    	return sourceFileLinkPattern != null;
    }

    @SuppressWarnings("boxing")
    public @CheckForNull URL getSourceLink(BugInstance b) {
		if (sourceFileLinkPattern == null)
			return null;

		SourceLineAnnotation src = b.getPrimarySourceLineAnnotation();
		String fileName = src.getSourcePath();
		int startLine = src.getStartLine();

		java.util.regex.Matcher m = sourceFileLinkPattern.matcher(fileName);
		boolean isMatch = m.matches();
		if (isMatch)
			try {
				URL link;
				if (startLine > 0)
					link = new URL(String.format(sourceFileLinkFormatWithLine, m.group(1), startLine, startLine - 10));
				else
					link = new URL(String.format(sourceFileLinkFormat, m.group(1)));
				return link;
			} catch (Exception e) {
				AnalysisContext.logError("Error generating source link for " + src, e);
			}

		return null;

	}

    public String getSourceLinkToolTip(BugInstance b) {
	    return sourceFileLinkToolTip;
    }
}
