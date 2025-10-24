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

public class FairphoneFirmwareDatabase {

    private Connection conn = null;

    public FairphoneFirmwareDatabase(String file) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);

            conn.prepareStatement("CREATE TABLE IF NOT EXISTS firmware (Model varchar(255), Version varchar(512))")
                    .executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    private boolean checkModelExists(String model) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT Version FROM firmware WHERE MODEL = ?");
            ps.setString(1, model);
            ResultSet rs = ps.executeQuery();

            return rs.next();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return false;
    }

    public void setFirmwareVersion(String model, String version) {
        try {
            PreparedStatement ps;

            if (checkModelExists(model))
                ps = conn.prepareStatement("UPDATE firmware SET Version = ? WHERE Model = ?");
            else
                ps = conn.prepareStatement("INSERT INTO firmware (Version, Model) VALUES (?, ?)");

            ps.setString(1, version);
            ps.setString(2, model);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public String getFirmwareVersion(String model) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT Version FROM firmware WHERE Model = ?");
            ps.setString(1, model);
            ResultSet rs = ps.executeQuery();
            String version = rs.getString("Version");

            return version != null ? version : "";
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return "";
    }
}
