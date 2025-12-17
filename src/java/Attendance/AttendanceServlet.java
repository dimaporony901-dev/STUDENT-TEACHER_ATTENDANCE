package Attendance;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalTime;

@WebServlet("/AttendanceServlet")
public class AttendanceServlet extends HttpServlet {

    private Connection getConn() throws SQLException {
         try {
        // Explicitly load MySQL JDBC driver (important for servlet containers)
        Class.forName("com.mysql.cj.jdbc.Driver");
    } catch (ClassNotFoundException e) {
        throw new SQLException("MySQL JDBC Driver not found", e);
    }

    return DriverManager.getConnection(
        "jdbc:mysql://localhost:3306/ATTENDANCE?useSSL=false&serverTimezone=UTC",
        "root",
        "Dimaporo-12345"
    );
    }
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        PrintWriter out = response.getWriter();
        JSONObject res = new JSONObject();
        try {
            JSONObject body = readJSON(request);
            String action = body.getString("action");
            switch (action) {
                case "login": login(body, request, res); break;
                case "getStudentSections": getStudentSections(request,res); break;
                case "studentPresent": studentPresent(body,request,res); break;
                case "getAllStudents": getAllStudents(res); break;
                case "addStudent": addStudent(body.getJSONObject("student"),res); break;
                case "updateStudent": updateStudent(body.getJSONObject("student"),res); break;
                case "deleteStudent": deleteStudent(body.getInt("id"),res); break;
                case "getTeacherSections": getTeacherSections(request,res); break;
                case "saveTeacherAttendance": saveTeacherAttendance(body,request,res); break;
                case "getAllTeachers": getAllTeachers(res); break;
                case "addTeacher": addTeacher(body.getJSONObject("teacher"),res); break;
                case "updateTeacher": updateTeacher(body.getJSONObject("teacher"),res); break;
                case "deleteTeacher": deleteTeacher(body.getInt("id"),res); break;
                case "createSectionWithStudents": createSectionWithStudents(body,request,res); break;
                case "deleteSection": deleteSection(body,request,res); break;
                case "getSectionStudents": getSectionStudents(body,res); break;
                default: res.put("status","error"); res.put("message","Invalid action");
            }
        } catch(Exception e){
            res.put("status","error");
            res.put("message", 
            e.getMessage() != null ? e.getMessage() : "Request failed. Please login again."
    );
        }
        out.print(res.toString());
    }

    // ------------------- METHODS -------------------
    private JSONObject readJSON(HttpServletRequest request) throws IOException {
        BufferedReader br=request.getReader();
        StringBuilder sb=new StringBuilder(); String line;
        while((line=br.readLine())!=null) sb.append(line);
        return new JSONObject(sb.toString());
    }

    private void requireRole(HttpSession session, String role) throws Exception {
        if(session==null || !role.equals(session.getAttribute("role"))) throw new Exception("Unauthorized");
    }

    private void login(JSONObject body, HttpServletRequest req, JSONObject res) throws Exception {
        int id=body.getInt("id"); String password=body.getString("password"), role=body.getString("role");
        String table = role.equals("student")?"students":"teachers";
        try(Connection conn=getConn();
            PreparedStatement ps=conn.prepareStatement("SELECT name FROM "+table+" WHERE id=? AND password=?")){
            ps.setInt(1,id); ps.setString(2,password);
            try(ResultSet rs=ps.executeQuery()){
                if(rs.next()){
                    HttpSession session=req.getSession();
                    session.setAttribute("userId",id);
                    session.setAttribute("role",role);
                    session.setAttribute("name",rs.getString("name"));
                    res.put("status","success"); res.put("name",rs.getString("name"));
                } else { res.put("status","fail"); res.put("message","Invalid credentials"); }
            }
        }
    }

    // ------------------- STUDENT -------------------
    private void getStudentSections(HttpServletRequest req, JSONObject res) throws Exception {
        HttpSession session=req.getSession(false);
        requireRole(session,"student");
        int studentId=(int)session.getAttribute("userId");
        JSONArray arr=new JSONArray();
        try(Connection conn=getConn();
            PreparedStatement ps=conn.prepareStatement(
                "SELECT s.id, s.section_name, s.subject, s.schedule_start, s.schedule_end " +
                "FROM sections s JOIN section_students ss ON s.id=ss.section_id WHERE ss.student_id=?"
            )) {
            ps.setInt(1,studentId);
            try(ResultSet rs=ps.executeQuery()){
                while(rs.next()){
                    JSONObject o=new JSONObject();
                    o.put("sectionId",rs.getInt("id"));
                    o.put("section",rs.getString("section_name"));
                    o.put("subject",rs.getString("subject"));
                    o.put("schedule",rs.getTime("schedule_start")+" - "+rs.getTime("schedule_end"));
                    arr.put(o);
                }
            }
        }
        res.put("status","success"); res.put("data",arr);
    }

    private void studentPresent(JSONObject body,HttpServletRequest req,JSONObject res) throws Exception {
        HttpSession session=req.getSession(false); requireRole(session,"student");
        int studentId=(int)session.getAttribute("userId"); int sectionId=body.getInt("sectionId");
        LocalDate today=LocalDate.now(); LocalTime now=LocalTime.now();
        try(Connection conn=getConn()){
            PreparedStatement psSec=conn.prepareStatement("SELECT schedule_start,schedule_end FROM sections WHERE id=?");
            psSec.setInt(1,sectionId); try(ResultSet rs=psSec.executeQuery()){ if(!rs.next()){
                res.put("status","fail"); res.put("message","Invalid section"); return; }
                LocalTime start=rs.getTime("schedule_start").toLocalTime();
                LocalTime end=rs.getTime("schedule_end").toLocalTime();
                if(now.isBefore(start)||now.isAfter(end)){
                    res.put("status","fail"); res.put("message","Attendance allowed only during schedule"); return;
                }
            }
            PreparedStatement check=conn.prepareStatement(
                    "SELECT id FROM attendance WHERE student_id=? AND section_id=? AND date=?");
            check.setInt(1,studentId); check.setInt(2,sectionId); check.setDate(3,Date.valueOf(today));
            try(ResultSet rs=check.executeQuery()){ if(rs.next()){
                res.put("status","fail"); res.put("message","Attendance already recorded today"); return; }
            }
            PreparedStatement ins=conn.prepareStatement(
                    "INSERT INTO attendance(student_id,section_id,date,status) VALUES(?,?,?,?)");
            ins.setInt(1,studentId); ins.setInt(2,sectionId); ins.setDate(3,Date.valueOf(today)); ins.setString(4,"P"); ins.executeUpdate();
        }
        res.put("status","success");
    }

    private void getAllStudents(JSONObject res) throws Exception {
    JSONArray arr = new JSONArray();
    try (Connection conn = getConn();
         Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT * FROM students")) {

        while (rs.next()) {
            JSONObject o = new JSONObject();
            o.put("id", rs.getInt("id"));
            o.put("name", rs.getString("name"));
            o.put("password", rs.getString("password")); // added
            o.put("program", rs.getString("program"));
            o.put("year", rs.getString("year"));
            arr.put(o);
        }
    }
    res.put("status", "success");
    res.put("data", arr);
}

    private void addStudent(JSONObject s,JSONObject res)throws Exception{
        try(Connection conn=getConn();
            PreparedStatement ps=conn.prepareStatement("INSERT INTO students(id,name,password,program,year) VALUES(?,?,?,?,?)")){
            ps.setInt(1,s.getInt("id"));
            ps.setString(2,s.getString("name"));
            ps.setString(3,s.getString("password"));
            ps.setString(4,s.getString("program"));
            ps.setString(5,s.getString("year"));
            ps.executeUpdate(); res.put("status","success");
        }
    }

    private void updateStudent(JSONObject s,JSONObject res)throws Exception{
        try(Connection conn=getConn();
            PreparedStatement ps=conn.prepareStatement("UPDATE students SET name=?,password=?,program=?,year=? WHERE id=?")){
            ps.setString(1,s.getString("name"));
            ps.setString(2,s.getString("password"));
            ps.setString(3,s.getString("program"));
            ps.setString(4,s.getString("year"));
            ps.setInt(5,s.getInt("id"));
            ps.executeUpdate(); res.put("status","success");
        }
    }

    private void deleteStudent(int id,JSONObject res)throws Exception{
        try(Connection conn=getConn();
            PreparedStatement ps=conn.prepareStatement("DELETE FROM students WHERE id=?")){
            ps.setInt(1,id); ps.executeUpdate(); res.put("status","success");
        }
    }

    // ------------------- TEACHER -------------------
    private void getTeacherSections(HttpServletRequest req, JSONObject res) throws Exception {
        HttpSession session=req.getSession(false); requireRole(session,"teacher");
        int teacherId=(int)session.getAttribute("userId");
        JSONArray arr=new JSONArray();
        try(Connection conn=getConn();
            PreparedStatement ps=conn.prepareStatement(
                    "SELECT id,section_name,subject,schedule_start,schedule_end FROM sections WHERE teacher_id=?")){
            ps.setInt(1,teacherId); try(ResultSet rs=ps.executeQuery()){
                while(rs.next()){
                    JSONObject o=new JSONObject();
                    o.put("sectionId",rs.getInt("id"));
                    o.put("section",rs.getString("section_name"));
                    o.put("subject",rs.getString("subject"));
                    o.put("schedule",rs.getTime("schedule_start")+" - "+rs.getTime("schedule_end"));
                    arr.put(o);
                }
            }
        }
        res.put("status","success"); res.put("data",arr);
    }

    private void saveTeacherAttendance(JSONObject body,HttpServletRequest req,JSONObject res)throws Exception{
        HttpSession session=req.getSession(false); requireRole(session,"teacher");
        JSONArray list=body.getJSONArray("attendance");
        try(Connection conn=getConn()){
            PreparedStatement psCheck=conn.prepareStatement(
                    "SELECT id FROM attendance WHERE section_id=? AND student_id=? AND date=?");
            PreparedStatement psIns=conn.prepareStatement(
                    "INSERT INTO attendance(section_id,student_id,date,status) VALUES(?,?,?,?)");
            PreparedStatement psUpd=conn.prepareStatement(
                    "UPDATE attendance SET status=? WHERE section_id=? AND student_id=? AND date=?");
            for(int i=0;i<list.length();i++){
                JSONObject a=list.getJSONObject(i);
                Date date=Date.valueOf(a.getString("date"));
                psCheck.setInt(1,a.getInt("sectionId"));
                psCheck.setInt(2,a.getInt("studentId"));
                psCheck.setDate(3,date);
                try(ResultSet rs=psCheck.executeQuery()){
                    if(rs.next()){
                        psUpd.setString(1,a.getString("status"));
                        psUpd.setInt(2,a.getInt("sectionId"));
                        psUpd.setInt(3,a.getInt("studentId"));
                        psUpd.setDate(4,date);
                        psUpd.executeUpdate();
                    } else {
                        psIns.setInt(1,a.getInt("sectionId"));
                        psIns.setInt(2,a.getInt("studentId"));
                        psIns.setDate(3,date);
                        psIns.setString(4,a.getString("status"));
                        psIns.executeUpdate();
                    }
                }
            }
        }
        res.put("status","success");
    }

    private void getAllTeachers(JSONObject res) throws Exception {
    JSONArray arr = new JSONArray();
    try (Connection conn = getConn();
         Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT * FROM teachers")) {

        while (rs.next()) {
            JSONObject o = new JSONObject();
            o.put("id", rs.getInt("id"));
            o.put("name", rs.getString("name"));
            o.put("password", rs.getString("password")); // added
            arr.put(o);
        }
    }
    res.put("status", "success");
    res.put("data", arr);
}

    private void addTeacher(JSONObject t,JSONObject res)throws Exception{
        try(Connection conn=getConn(); PreparedStatement ps=conn.prepareStatement("INSERT INTO teachers(id,name,password) VALUES(?,?,?)")){
            ps.setInt(1,t.getInt("id")); ps.setString(2,t.getString("name")); ps.setString(3,t.getString("password")); ps.executeUpdate();
            res.put("status","success");
        }
    }

    private void updateTeacher(JSONObject t,JSONObject res)throws Exception{
        try(Connection conn=getConn();PreparedStatement ps=conn.prepareStatement("UPDATE teachers SET name=?,password=? WHERE id=?")){
            ps.setString(1,t.getString("name")); ps.setString(2,t.getString("password")); ps.setInt(3,t.getInt("id")); ps.executeUpdate(); res.put("status","success");
        }
    }

    private void deleteTeacher(int id,JSONObject res)throws Exception{
        try(Connection conn=getConn();PreparedStatement ps=conn.prepareStatement("DELETE FROM teachers WHERE id=?")){
            ps.setInt(1,id); ps.executeUpdate(); res.put("status","success");
        }
    }

    private void createSectionWithStudents(JSONObject body,HttpServletRequest req,JSONObject res)throws Exception{
        HttpSession session=req.getSession(false); requireRole(session,"teacher");
        int teacherId=(int)session.getAttribute("userId");
        String section=body.getString("section");
        String subject=body.getString("subject");
        Time start=Time.valueOf(body.getString("start"));
        Time end=Time.valueOf(body.getString("end"));
        JSONArray students=body.getJSONArray("students");
        try(Connection conn=getConn()){
            PreparedStatement ps=conn.prepareStatement("INSERT INTO sections(teacher_id,section_name,subject,schedule_start,schedule_end) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1,teacherId); ps.setString(2,section); ps.setString(3,subject); ps.setTime(4,start); ps.setTime(5,end);
            ps.executeUpdate();
            int sectionId=0;
            try(ResultSet rs=ps.getGeneratedKeys()){ if(rs.next()) sectionId=rs.getInt(1);}
            PreparedStatement psSS=conn.prepareStatement("INSERT INTO section_students(section_id,student_id) VALUES(?,?)");
            for(int i=0;i<students.length();i++){ psSS.setInt(1,sectionId); psSS.setInt(2,students.getInt(i)); psSS.executeUpdate(); }
        }
        res.put("status","success");
    }

    private void deleteSection(JSONObject body,HttpServletRequest req,JSONObject res)throws Exception{
        HttpSession session=req.getSession(false); requireRole(session,"teacher");
        int sectionId=body.getInt("sectionId");
        try(Connection conn=getConn();PreparedStatement ps=conn.prepareStatement("DELETE FROM sections WHERE id=?")){
            ps.setInt(1,sectionId); ps.executeUpdate(); res.put("status","success");
        }
    }

   private void getSectionStudents(JSONObject body, JSONObject res) throws Exception {
    int sectionId = body.getInt("sectionId");
    JSONArray arr = new JSONArray();

    try (Connection conn = getConn()) {
        PreparedStatement ps = conn.prepareStatement(
            "SELECT u.id AS studentId, u.name, u.program, u.year, " +
            "SUM(CASE WHEN a.status='P' THEN 1 ELSE 0 END) AS present, " +
            "SUM(CASE WHEN a.status='A' THEN 1 ELSE 0 END) AS absent, " +
            "SUM(CASE WHEN a.status='E' THEN 1 ELSE 0 END) AS excused, " +
            "COALESCE((SELECT status FROM attendance a2 WHERE a2.student_id=u.id AND a2.section_id=? ORDER BY date DESC LIMIT 1), '') AS status " +
            "FROM section_students ss " +
            "JOIN students u ON ss.student_id = u.id " +
            "LEFT JOIN attendance a ON a.student_id = u.id AND a.section_id = ss.section_id " +
            "WHERE ss.section_id=? " +
            "GROUP BY u.id, u.name, u.program, u.year"
        );
        ps.setInt(1, sectionId);
        ps.setInt(2, sectionId);

        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            JSONObject o = new JSONObject();
            o.put("studentId", rs.getInt("studentId"));
            o.put("name", rs.getString("name"));
            o.put("program", rs.getString("program"));
            o.put("year", rs.getString("year"));
            o.put("present", rs.getInt("present"));
            o.put("absent", rs.getInt("absent"));
            o.put("excused", rs.getInt("excused"));
            o.put("status", rs.getString("status"));
            arr.put(o);
        }
    }

    res.put("status", "success");
    res.put("data", arr);
}

}
