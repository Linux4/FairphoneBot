/*
   Copyright (C) 2025  Tim Zimmermann <tim@linux4.de>

   This program is free software: you can redistribute it and/or modify
   it under the terms of the GNU Affero General Public License as
   published by the Free Software Foundation, either version 3 of the
   License, or (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU Affero General Public License for more details.

   You should have received a copy of the GNU Affero General Public License
   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.linux4.fairphonebot;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FairphoneGerritDatabase {

    private Connection conn = null;

    public FairphoneGerritDatabase(String file) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);

            conn.prepareStatement("CREATE TABLE IF NOT EXISTS projects (Name varchar(512))").executeUpdate();
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS branches (Project varchar(512), Name varchar(512), Revision varchar(512))")
                    .executeUpdate();
            conn.prepareStatement("CREATE TABLE IF NOT EXISTS tags (Project varchar(512), Name varchar(512))")
                    .executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public List<String> getProjects() {
        List<String> projects = new ArrayList<>();

        try {
            PreparedStatement ps = conn.prepareStatement("SELECT Name FROM projects");
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                projects.add(rs.getString("Name"));
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return projects;
    }

    public void addProject(String name) {
        try {
            PreparedStatement ps = conn.prepareStatement("INSERT INTO projects (Name) VALUES (?)");
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public void removeProject(String name) {
        try {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM projects WHERE Name = ?");
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public Map<String, String> getBranches(String project) {
        HashMap<String, String> branches = new HashMap<>();

        try {
            PreparedStatement ps = conn.prepareStatement("SELECT Name, Revision FROM branches WHERE Project = ?");
            ps.setString(1, project);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                branches.put(rs.getString("Name"), rs.getString("Revision"));
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return branches;
    }

    public void addBranch(String project, String name) {
        try {
            PreparedStatement ps = conn.prepareStatement("INSERT INTO branches (Project, Name) VALUES (?, ?)");
            ps.setString(1, project);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public void removeBranch(String project, String name) {
        try {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM branches WHERE Project = ? AND Name = ?");
            ps.setString(1, project);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public void setBranchRevision(String project, String name, String revision) {
        try {
            PreparedStatement ps = conn.prepareStatement("UPDATE branches SET Revision = ? WHERE Project = ? AND Name = ?");
            ps.setString(1, revision);
            ps.setString(2, project);
            ps.setString(3, name);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public List<String> getTags(String project) {
        List<String> tags = new ArrayList<>();

        try {
            PreparedStatement ps = conn.prepareStatement("SELECT Name FROM tags WHERE Project = ?");
            ps.setString(1, project);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                tags.add(rs.getString("Name"));
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return tags;
    }

    public void addTag(String project, String name) {
        try {
            PreparedStatement ps = conn.prepareStatement("INSERT INTO tags (Project, Name) VALUES (?, ?)");
            ps.setString(1, project);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public void removeTag(String project, String name) {
        try {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM tags WHERE Project = ? AND Name = ?");
            ps.setString(1, project);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }
}
