package Student_Management.class_service.repository;

import Student_Management.class_service.entity.Class;
import Student_Management.class_service.entity.ClassFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClassFileRepository extends JpaRepository<ClassFile, Long> {
    //take all uploaded files of this class
    List<ClassFile> findByClassroomOrderByCreatedAtDesc(Class classroom);

}